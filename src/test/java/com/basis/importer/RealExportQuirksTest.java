package com.basis.importer;

import static com.basis.support.Fixtures.IBKR;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Account;
import com.basis.domain.CommodityClass;
import com.basis.domain.event.Buy;
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.Sell;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The things a real broker export does that a plausible fake does not.
 *
 * <p>Every case here comes from running a genuine Fidelity Accounts History file through the
 * importer for the first time. The numbers are changed, because a real trade is nobody
 * else's business, but each one reproduces a defect the real file exposed and no invented
 * fixture had.
 *
 * <p>The header is the real one, verbatim, which is the part worth keeping exactly.
 */
class RealExportQuirksTest {

    /**
     * The real header. Note {@code Settlement Date} at the end and {@code Price} before
     * {@code Quantity}, neither of which the first guess at this format had.
     */
    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,Description,"
            + "Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),Amount ($),"
            + "Settlement Date";

    private final StatementParser parser = new StatementParser(BrokerProfiles.load("fidelity"));

    @Test
    @DisplayName("the transaction date is Run Date, not the Settlement Date further along the header")
    void runDateWinsOverSettlementDate() {
        List<StatementRow> rows = parser.parse(List.of(HEADER,
                "08/13/2026,Individual,Z1,YOU SOLD SOME FUND (ABCDX) (Cash),ABCDX,SOME FUND,Cash,"
                        + "155.4,\"-3.217\",\"\",\"\",\"\",500,08/14/2026"), "real.csv");

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.date())
                .as("both columns are dates the profile could name, and they are different days")
                .isEqualTo(java.time.LocalDate.of(2026, 8, 13)));
    }

    @Test
    @DisplayName("a byte order mark before the header does not hide it")
    void byteOrderMarkIsStripped() {
        List<StatementRow> rows = parser.parse(List.of("﻿" + HEADER,
                "08/13/2026,Individual,Z1,YOU SOLD SOME FUND (ABCDX) (Cash),ABCDX,SOME FUND,Cash,"
                        + "155.4,\"-3.217\",\"\",\"\",\"\",500,08/14/2026"), "real.csv");

        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("the action text carries the security name and the account type, and still classifies")
    void actionTextIsAWholeSentence() {
        assertThat(BrokerProfiles.load("fidelity")
                .classify("YOU SOLD FIDELITY 500 INDEX FUND (FXAIX) (Cash)"))
                .contains(ActionKind.SELL);
        assertThat(BrokerProfiles.load("fidelity")
                .classify("Electronic Funds Transfer Received (Cash)"))
                .as("mixed case in the real file, upper case in the profile")
                .contains(ActionKind.CASH_TRANSFER);
    }

    @Test
    @DisplayName("the price comes from the money that moved, not from the rounded Price column")
    void priceIsDerivedFromTheAmount() {
        // 3.217 at a stated 155.4 is 499.92. The statement says the amount was 500.00.
        // Trusting the price column credits eight cents that never arrived, on every trade.
        LedgerEvent event = one("08/13/2026,Individual,Z1,YOU SOLD SOME FUND (ABCDX) (Cash),ABCDX,"
                + "SOME FUND,Cash,155.4,\"-3.217\",\"\",\"\",\"\",500,08/14/2026");

        assertThat(event).isInstanceOfSatisfying(Sell.class, sell -> {
            assertThat(sell.grossProceeds().toMajorUnits())
                    .as("exactly what the statement said the cash was")
                    .isEqualByComparingTo("500.00");
            assertThat(sell.quantity().value()).isEqualByComparingTo("3.217");
        });
    }

    @Test
    @DisplayName("charges are added back for a sale, so the price is of the shares alone")
    void chargesAreBackedOutOfTheDerivedPrice() {
        // Amount is net of the fee, so gross is 500.00 and the fee is expensed separately.
        LedgerEvent event = one("08/13/2026,Individual,Z1,YOU SOLD SOME FUND (ABCDX) (Cash),ABCDX,"
                + "SOME FUND,Cash,100,\"-5\",4.95,\"\",\"\",495.05,08/14/2026");

        assertThat(event).isInstanceOfSatisfying(Sell.class, sell -> {
            assertThat(sell.grossProceeds().toMajorUnits()).isEqualByComparingTo("500.00");
            assertThat(sell.commission().toMajorUnits()).isEqualByComparingTo("4.95");
        });
    }

    @Test
    @DisplayName("a reinvestment is only the purchase, because the dividend is its own row")
    void reinvestmentIsNotAlsoADistribution() {
        // The real export reports a reinvested distribution as two rows. Treating the
        // reinvestment as income as well counted the dividend twice and left the cash
        // balance wrong by the whole distribution.
        List<LedgerEvent> reinvestment = events("07/31/2026,Individual,Z1,"
                + "REINVESTMENT SOME MONEY MARKET (MMFXX) (Cash),MMFXX,SOME MONEY MARKET,Cash,"
                + "1,0.11,\"\",\"\",\"\",\"-0.11\",\"\"");

        assertThat(reinvestment)
                .as("the shares, and nothing else")
                .singleElement()
                .isInstanceOf(Buy.class);

        List<LedgerEvent> dividend = events("07/31/2026,Individual,Z1,"
                + "DIVIDEND RECEIVED SOME MONEY MARKET (MMFXX) (Cash),MMFXX,SOME MONEY MARKET,Cash,"
                + "\"\",0,\"\",\"\",\"\",0.11,\"\"");

        assertThat(dividend).singleElement().isInstanceOfSatisfying(CashDividend.class,
                paid -> assertThat(paid.grossAmount().toMajorUnits()).isEqualByComparingTo("0.11"));
    }

    @Test
    @DisplayName("a fund is a fund only because something declared it one")
    void commodityClassComesFromTheCatalog() {
        CommodityCatalog catalog = CommodityCatalog.parse(
                List.of("symbol,kind", "ABCDX,MUTUAL_FUND"), "commodities.csv");

        assertThat(catalog.resolve("ABCDX").commodityClass())
                .as("no statement column says this, and the description cannot be trusted to")
                .isEqualTo(CommodityClass.MUTUAL_FUND);
        assertThat(catalog.resolve("AAPL").commodityClass())
                .as("undeclared means equity, which only ever refuses average cost")
                .isEqualTo(CommodityClass.EQUITY);
    }

    @Test
    @DisplayName("the shipped catalog and the shipped profile both load")
    void shippedConfigIsValid() {
        assertThat(CommodityCatalog.load().size())
                .as("config/commodities.csv ships with the funds a real export contained")
                .isGreaterThan(0);
        assertThat(BrokerProfiles.load("fidelity").aliasesFor("date"))
                .as("settlement date is deliberately not a transaction date")
                .doesNotContain("settlement date");
    }

    private LedgerEvent one(String line) {
        List<LedgerEvent> events = events(line);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private List<LedgerEvent> events(String line) {
        StatementRow row = parser.parse(List.of(HEADER, line), "real.csv").get(0);
        return new StatementRowMapper(BrokerProfiles.load("fidelity"), IBKR,
                Account.of("Assets:Bank:External"), SymbolMapping.empty(), CommodityCatalog.empty(),
                "real.csv").toEvents(row);
    }
}
