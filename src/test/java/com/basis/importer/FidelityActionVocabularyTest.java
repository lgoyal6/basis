package com.basis.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Account;
import com.basis.domain.event.InterestEarned;
import com.basis.domain.event.LedgerEvent;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import com.basis.support.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The phrases the shipped Fidelity profile claims to understand.
 *
 * <p>Most of these were never produced by the one real export this project has seen. They come
 * from Fidelity's documented wording, added so a different account's first import is less
 * likely to stop on a phrase that was always going to be a config line. That makes them worth
 * pinning: an untested claim about a broker's vocabulary is a claim that quietly stops being
 * true the next time somebody edits the file.
 *
 * <p>This asserts classification, which is all a profile can be held to. Whether Fidelity
 * really writes "CREDIT INTEREST" is a question only a real statement answers, and the cost of
 * being wrong is one edit to a properties file.
 */
class FidelityActionVocabularyTest {

    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date";

    private final BrokerProfile fidelity = BrokerProfiles.load("fidelity");

    @ParameterizedTest
    @CsvSource({
        "YOU BOUGHT FIDELITY 500 INDEX FUND (FXAIX) (Cash), BUY",
        "YOU SOLD FIDELITY 500 INDEX FUND (FXAIX) (Cash), SELL",
        "REINVESTMENT FIDELITY GOVERNMENT MMKT (SPAXX) (Cash), BUY",
        "DIVIDEND RECEIVED FIDELITY GOVERNMENT MMKT (SPAXX) (Cash), CASH_DIVIDEND",
        "LONG-TERM CAP GAIN SOME FUND (ABCDX), CASH_DIVIDEND",
        "RETURN OF CAPITAL SOME TRUST (ABC), CASH_DIVIDEND",
        "INTEREST EARNED (Cash), INTEREST",
        "BANK INTEREST, INTEREST",
        "MARGIN INTEREST CHARGED, FEE",
        "SHORT TERM REDEMPTION FEE SOME FUND (ABCDX), FEE",
        "ACCOUNT FEE, FEE",
        "FEDERAL TAX WITHHELD, WITHHOLDING",
        "FOREIGN TAX PAID SOME ADR (ABC), WITHHOLDING",
        "CHECK RECEIVED, CASH_TRANSFER",
        "ROLLOVER CONTRIBUTION, CASH_TRANSFER",
        "TRANSFERRED FROM BROKERAGE ACCOUNT, SECURITY_TRANSFER",
    })
    @DisplayName("every phrase the shipped profile claims classifies to what it claims")
    void shippedPhrasesClassify(String action, ActionKind expected) {
        assertThat(fidelity.classify(action)).contains(expected);
    }

    @Test
    @DisplayName("interest earned and interest charged are opposite signs and must not collide")
    void earnedAndChargedAreToldApart() {
        // Both begin with the same word. A profile that shortened either to a bare
        // "INTEREST" would book a margin charge as income, doubling the error: the expense
        // never appears and the income never happened.
        assertThat(fidelity.classify("INTEREST EARNED (Cash)")).contains(ActionKind.INTEREST);
        assertThat(fidelity.classify("INTEREST CHARGED (Margin)")).contains(ActionKind.FEE);
        assertThat(fidelity.knownPhrases())
                .as("no bare verb, because longest-prefix only protects phrases that stay specific")
                .doesNotContain("INTEREST");
    }

    @Test
    @DisplayName("a cash sweep still pays a dividend, because that is what the broker reports")
    void aSweepFundIsStillADividend() {
        assertThat(fidelity.classify("DIVIDEND RECEIVED FIDELITY GOVERNMENT MMKT (SPAXX) (Cash)"))
                .as("INTEREST is for rows naming no security, and this one names SPAXX")
                .contains(ActionKind.CASH_DIVIDEND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MERGER SOME CORP (ABC)",
        "NAME CHANGE SOME CORP (ABC)",
        "REVERSE SPLIT SOME CORP (ABC)",
        "DISTRIBUTION SOME TRUST (ABC)",
        "CASH IN LIEU OF FRACTIONAL SHARE SOME CORP (ABC)",
        "EXCHANGE SOME FUND (ABCDX)",
    })
    @DisplayName("a corporate action still stops the import, because a row cannot say on what terms")
    void corporateActionsAreDeliberatelyUnmapped(String action) {
        assertThat(fidelity.classify(action))
                .as("mapping this to let an import proceed would silently restate a cost basis")
                .isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("an interest row survives the whole importer, symbol column and all blank")
    void interestRowBecomesAnInterestEvent() {
        // An interest row names no security, so the columns that carry one are empty. That
        // is the part a mapper written around trades gets wrong: it reaches for a symbol
        // that is not there.
        StatementRow row = new StatementParser(fidelity).parse(java.util.List.of(HEADER,
                "08/31/2026,Individual,Z1,INTEREST EARNED (Cash),\"\",No Description,Cash,"
                        + "\"\",\"\",\"\",\"\",\"\",1.37,\"\""), "real.csv").get(0);

        java.util.List<LedgerEvent> events = new StatementRowMapper(fidelity, Fixtures.IBKR,
                Account.of("Assets:Bank:External"), SymbolMapping.empty(), CommodityCatalog.empty(),
                "real.csv").toEvents(row);

        assertThat(events).singleElement().isInstanceOfSatisfying(InterestEarned.class, earned -> {
            assertThat(earned.grossAmount().toMajorUnits()).isEqualByComparingTo("1.37");
            assertThat(earned.netAmount().toMajorUnits())
                    .as("nothing withheld unless a separate row says so")
                    .isEqualByComparingTo("1.37");
        });
    }

    @Test
    @DisplayName("nothing was mapped to IGNORE, so no row is dropped without someone deciding to")
    void nothingIsIgnoredByDefault() {
        assertThat(fidelity.classify("SOME ROW NOBODY HAS EVER SEEN")).isEmpty();
    }
}
