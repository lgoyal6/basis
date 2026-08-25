package com.basis.reconcile;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.EUR;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.qty;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Commodity;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.event.Buy;
import com.basis.domain.event.OpeningBalance;
import com.basis.ledger.Ledger;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A holding bought in one currency and reported in another.
 *
 * <p>This existed as a silent wrong answer. {@code LedgerState.openBasis} takes a currency and
 * skips every lot not held in it, so asking for USD about a position bought in euros returned
 * zero. Reconciliation compared the broker's real cost against that zero and produced a large,
 * confidently worded break saying the entire cost basis was missing. Nothing failed and
 * nothing was flagged: it just lied.
 *
 * <p>Three outcomes now, and these tests are one apiece: it agrees once translated, it still
 * disagrees after translating, or there is no rate and no comparison can be made.
 */
class ForeignCurrencyBasisTest {

    private static final LocalDate AS_OF = JAN_15.plusMonths(3);

    /** 100 shares at 90.00 EUR, so a cost basis of 9,000.00 EUR and nothing in dollars. */
    private static com.basis.ledger.LedgerState boughtInEuros() {
        Ledger ledger = new Ledger();
        ledger.record(new OpeningBalance(JAN_15, IBKR, "cash", "{}",
                Commodity.of(EUR), qty("100000"), null));
        ledger.record(new Buy(JAN_15.plusDays(1), IBKR, "buy", "{}", AAPL,
                qty("100"), Price.of("90.00", EUR), Money.zero(EUR)));
        return ledger.state();
    }

    private static ExchangeRates fixed(String rate) {
        return (from, to, on) -> Optional.of(
                new ExchangeRates.Quote(new BigDecimal(rate), on, "test"));
    }

    private static List<BreakRecord> reconcile(ExchangeRates rates, String reportedUsdBasis) {
        BrokerSnapshot snapshot = new BrokerSnapshot(IBKR, AS_OF, SnapshotScope.SECURITIES_ONLY,
                List.of(BrokerPositions.held(IBKR, AAPL, qty("100"),
                        Money.of(new BigDecimal(reportedUsdBasis), USD))));
        return new Reconciler(SplitCalendar.EMPTY, com.basis.reference.SymbolMapping.empty(), rates)
                .reconcile(boughtInEuros(), snapshot);
    }

    @Test
    @DisplayName("a cost that agrees once translated is not a break at all")
    void agreesAfterTranslation() {
        // 9,000.00 EUR at 1.10 is 9,900.00 USD, which is what the broker says.
        assertThat(reconcile(fixed("1.10"), "9900.00"))
                .as("the ledger and the broker are describing the same cost in different money")
                .isEmpty();
    }

    @Test
    @DisplayName("a difference that survives translation says so, and names the rate it used")
    void stillDisagreesAfterTranslation() {
        assertThat(reconcile(fixed("1.10"), "10000.00")).singleElement().satisfies(record -> {
            assertThat(record.cause().code()).isEqualTo(ProbableCause.FX_TRANSLATION);
            assertThat(record.cause().explanation())
                    .as("the rate has to be in the sentence or the number cannot be checked")
                    .contains("1.10")
                    .contains("9000.00 EUR")
                    .contains("9900.00 USD");
            assertThat(record.computedAmount().toMajorUnits()).isEqualByComparingTo("9900.00");
        });
    }

    @Test
    @DisplayName("with no rate available basis refuses to compare rather than comparing to zero")
    void withoutARateItRefuses() {
        assertThat(reconcile(ExchangeRates.NONE, "9900.00")).singleElement().satisfies(record -> {
            assertThat(record.cause().code()).isEqualTo(ProbableCause.CURRENCY_NOT_COMPARABLE);
            assertThat(record.cause().explanation())
                    .as("this is the bug: it used to claim the whole cost was missing")
                    .contains("9000.00 EUR")
                    .contains("no EUR to USD rate is available");
            assertThat(record.cause().suggestedAction()).contains("refresh-fx EURUSD");
            assertThat(record.computedAmount())
                    .as("no number is offered, because none can honestly be computed")
                    .isNull();
        });
    }

    @Test
    @DisplayName("the old lookup still returns zero for a currency not held, and is now documented as such")
    void theUnderlyingLookupIsStillHonestlyUseless() {
        // The holding account, not the broker root: lots live under Assets:Broker:IBKR:AAPL.
        com.basis.domain.Account holding = com.basis.ledger.LedgerAccounts.holding(IBKR, AAPL);

        assertThat(boughtInEuros().openBasis(holding, AAPL, USD).toMajorUnits())
                .as("true and useless: nothing was bought in dollars, and this is the silent"
                        + " zero that reconciliation used to report as a missing cost basis")
                .isEqualByComparingTo("0.00");
        assertThat(boughtInEuros().openBasisByCurrency(holding, AAPL))
                .as("the whole picture, which is what a comparison needs")
                .containsOnlyKeys(EUR);
        assertThat(boughtInEuros().openBasisByCurrency(holding, AAPL).get(EUR).toMajorUnits())
                .isEqualByComparingTo("9000.00");
    }

    @Test
    @DisplayName("a rate from an earlier day is used but reported as being from that day")
    void aStaleRateSaysWhenItIsFrom() {
        LocalDate friday = AS_OF.minusDays(2);
        ExchangeRates weekend = (from, to, on) -> Optional.of(
                new ExchangeRates.Quote(new BigDecimal("1.10"), friday, "test"));

        assertThat(reconcile(weekend, "10000.00")).singleElement()
                .satisfies(record -> assertThat(record.cause().explanation())
                        .as("currency markets close, and a reader should know which day the rate is")
                        .contains("from " + friday));
    }

    @Test
    @DisplayName("a same currency difference is still an ordinary basis drift")
    void sameCurrencyIsUnchanged() {
        Ledger ledger = new Ledger();
        ledger.record(new OpeningBalance(JAN_15, IBKR, "cash", "{}",
                Commodity.of(USD), qty("100000"), null));
        ledger.record(new Buy(JAN_15.plusDays(1), IBKR, "buy", "{}", AAPL,
                qty("100"), Price.of("90.00", USD), Money.zero(USD)));

        BrokerSnapshot snapshot = new BrokerSnapshot(IBKR, AS_OF, SnapshotScope.SECURITIES_ONLY,
                List.of(BrokerPositions.held(IBKR, AAPL, qty("100"),
                        Money.of(new BigDecimal("9100.00"), USD))));

        assertThat(new Reconciler(SplitCalendar.EMPTY).reconcile(ledger.state(), snapshot))
                .singleElement()
                .satisfies(record -> assertThat(record.cause().code())
                        .isEqualTo(ProbableCause.BASIS_DRIFT));
    }
}
