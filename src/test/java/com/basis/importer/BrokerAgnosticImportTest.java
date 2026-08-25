package com.basis.importer;

import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.ledger.LedgerAccounts;
import com.basis.ledger.LedgerState;
import com.basis.persistence.DerivedStateProjector;
import com.basis.persistence.DerivedStateRepository;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The claim that adding a broker costs a config file and nothing else, tested rather than
 * asserted.
 *
 * <p>No Java was written for Schwab. Its columns are named differently, ordered differently
 * and worded differently from Fidelity's, and it imports because
 * {@code config/brokers/schwab.properties} exists.
 */
@SpringBootTest
@Testcontainers
class BrokerAgnosticImportTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Account SCHWAB = Account.of("Assets:Broker:Schwab");
    private static final Account EXTERNAL = Account.of("Assets:Bank:External");
    private static final Commodity MSFT = Commodity.equity("MSFT");

    /** Quoted everywhere, dollar signs, a different column set, a different vocabulary. */
    private static final String SCHWAB_EXPORT = """
            "Transactions for account Individual XXXX-1234 as of 08/24/2026"

            "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
            "01/06/2026","MoneyLink Transfer","","Funds Received","","","","$25000.00"
            "01/20/2026","Buy","MSFT","MICROSOFT CORP","20","$410.50","$4.95","-$8214.95"
            "02/14/2026","Qualified Dividend","MSFT","MICROSOFT CORP","","","","$16.60"
            "03/05/2026","Sell","MSFT","MICROSOFT CORP","-8","$430.00","$4.95","$3435.05"

            "Transactions Total","","","","","","",""
            """;

    @Autowired
    JdbcClient db;

    @Autowired
    ImportService importer;

    @Autowired
    DerivedStateProjector projector;

    @Autowired
    DerivedStateRepository derived;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM import_batch").update();
        derived.truncate();
    }

    @Test
    @DisplayName("a Schwab export imports correctly, with no Java written for Schwab")
    void schwabImportsFromItsProfileAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("schwab.csv");
        Files.writeString(file, SCHWAB_EXPORT, StandardCharsets.UTF_8);

        ImportReport report = importer.importStatement(
                file, BrokerProfiles.load("schwab"), SCHWAB, EXTERNAL, SymbolMapping.empty(),
                CommodityCatalog.empty());

        assertThat(report.eventsRecorded()).isEqualTo(4);

        LedgerState state = projector.project();
        assertThat(state.position(LedgerAccounts.holding(SCHWAB, MSFT), MSFT).value())
                .as("bought 20, sold 8")
                .isEqualByComparingTo("12");
        // 25000 in, 8214.95 out, 16.60 dividend, 3435.05 from the sale.
        assertThat(state.cash(LedgerAccounts.cash(SCHWAB), USD)).isEqualTo(usd("20236.70"));
        assertThat(state.realizedGains()).singleElement().satisfies(gain -> {
            assertThat(gain.basis())
                    .as("8 shares that cost 410.50 each")
                    .isEqualTo(usd("3284.00"));
            assertThat(gain.gain()).isEqualTo(usd("156.00"));
        });
    }

    @Test
    @DisplayName("the same ledger takes statements from two brokers side by side")
    void twoBrokersInOneLedger(@TempDir Path dir) throws Exception {
        Path schwab = dir.resolve("schwab.csv");
        Files.writeString(schwab, SCHWAB_EXPORT, StandardCharsets.UTF_8);
        Path fidelity = dir.resolve("fidelity.csv");
        Files.writeString(fidelity, """
                Run Date,Action,Symbol,Description,Quantity,Price ($),Commission ($),Amount ($)
                01/15/2026,YOU BOUGHT,AAPL,"APPLE INC, COM",10,150.00,1.00,-1501.00
                """, StandardCharsets.UTF_8);

        importer.importStatement(schwab, BrokerProfiles.load("schwab"), SCHWAB, EXTERNAL,
                SymbolMapping.empty(), CommodityCatalog.empty());
        importer.importStatement(fidelity, BrokerProfiles.load("fidelity"),
                Account.of("Assets:Broker:Fidelity"), EXTERNAL, SymbolMapping.empty(),
                CommodityCatalog.empty());

        LedgerState state = projector.project();
        assertThat(state.position(LedgerAccounts.holding(SCHWAB, MSFT), MSFT).value())
                .isEqualByComparingTo("12");
        assertThat(state.position(
                LedgerAccounts.holding(Account.of("Assets:Broker:Fidelity"), Commodity.equity("AAPL")),
                Commodity.equity("AAPL")).value())
                .as("a broker is a prefix in the account tree, so two of them just coexist")
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("every shipped profile parses, so a broken one is caught here and not by a user")
    void shippedProfilesAreValid() {
        List<String> brokers = BrokerProfiles.available();

        assertThat(brokers).contains("fidelity", "schwab");
        for (String broker : brokers) {
            BrokerProfile profile = BrokerProfiles.load(broker);
            assertThat(profile.name()).isNotBlank();
            assertThat(profile.knownPhrases())
                    .as("%s maps at least one action phrase", broker)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("a profile naming no header for a required column is refused")
    void refusesAProfileMissingARequiredColumn() {
        Properties properties = new Properties();
        properties.setProperty("profile.name", "Broken");
        properties.setProperty("date.formats", "MM/dd/yyyy");
        properties.setProperty("column.date", "Date");
        properties.setProperty("column.action", "Action");
        properties.setProperty("action.BUY", "BUY");

        assertThatThrownBy(() -> BrokerProfiles.parse(properties, "broken.properties"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no header for the amount column");
    }

    @Test
    @DisplayName("a key nothing reads is a typo, and is refused rather than silently ignored")
    void refusesAnUnknownKey() {
        Properties properties = new Properties();
        properties.setProperty("profile.name", "Typo");
        properties.setProperty("date.formats", "MM/dd/yyyy");
        properties.setProperty("column.date", "Date");
        properties.setProperty("column.action", "Action");
        properties.setProperty("column.amount", "Amount");
        properties.setProperty("action.BUY", "BUY");
        properties.setProperty("column.commissions", "Commission");

        assertThatThrownBy(() -> BrokerProfiles.parse(properties, "typo.properties"))
                .as("column.commissions would silently leave commissions unmapped")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing reads")
                .hasMessageContaining("column.commissions");
    }

    @Test
    @DisplayName("an unknown broker names the profiles that do exist")
    void unknownBrokerIsHelpful() {
        assertThatThrownBy(() -> BrokerProfiles.load("etrade"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no broker profile")
                .hasMessageContaining("fidelity")
                .hasMessageContaining("not code");
    }
}
