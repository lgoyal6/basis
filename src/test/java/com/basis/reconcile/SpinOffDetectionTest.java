package com.basis.reconcile;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Commodity;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.event.Buy;
import com.basis.domain.event.OpeningBalance;
import com.basis.ledger.Ledger;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shares that appeared without a purchase, and why basis will not call that a spin off.
 *
 * <p>This started as a classifier. It emitted its own cause code and displaced
 * {@code UNKNOWN_HOLDING} whenever an unexplained holding divided cleanly into something else
 * held, and it was wrong within minutes of being written: in the demo, 15 shares of one
 * security are exactly 0.75 per share of another with no relationship to it at all.
 *
 * <p>That is not a bug in the bounds, it is the shape of the problem. Halves, quarters and
 * three quarters occur constantly between unrelated positions, and there is no corporate
 * action feed here to corroborate one, so the ratio is arithmetic and not evidence. Exactly
 * the conclusion the split ratio detector reached, relearned in a new place.
 *
 * <p>So the break stays an unknown holding, and gains a sentence naming what basis noticed.
 * These tests pin that split: the observation is offered, the classification is not changed.
 */
class SpinOffDetectionTest {

    private static final Commodity SPINCO = Commodity.equity("SPINCO");
    private static final LocalDate AS_OF = JAN_15.plusMonths(6);

    @Test
    @DisplayName("a clean fraction per parent share is mentioned, not classified as a spin off")
    void aCleanFractionIsOfferedAsAPossibility() {
        List<BreakRecord> breaks = reconcile(qty("100"), reported(SPINCO, "25"));

        assertThat(breaks).singleElement().satisfies(record -> {
            assertThat(record.cause().code())
                    .as("still an unknown holding, because that is all basis actually knows")
                    .isEqualTo(ProbableCause.UNKNOWN_HOLDING);
            assertThat(record.cause().suggestedAction())
                    .contains("0.25 per share")
                    .contains("AAPL")
                    .contains("8937")
                    .as("and it says outright that the ratio is not evidence")
                    .contains("not evidence");
            assertThat(record.cause().confident()).isFalse();
        });
    }

    @Test
    @DisplayName("no cause code was invented for it, so nothing groups a guess with a finding")
    void thereIsNoSpinOffCauseCode() {
        assertThat(reconcile(qty("100"), reported(SPINCO, "25")))
                .allSatisfy(record -> assertThat(record.cause().code())
                        .isNotEqualTo("POSSIBLE_SPIN_OFF"));
    }

    @Test
    @DisplayName("a quantity that is no sensible fraction of anything held gets no hint at all")
    void animplausibleRatioSaysNothing() {
        assertThat(reconcile(qty("100"), reported(SPINCO, "4000")))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.cause().code()).isEqualTo(ProbableCause.UNKNOWN_HOLDING);
                    assertThat(record.cause().suggestedAction()).doesNotContain("8937");
                });
    }

    @Test
    @DisplayName("a ratio that only approximately explains the shares is not mentioned")
    void anInexactRatioSaysNothing() {
        assertThat(reconcile(qty("7"), reported(SPINCO, "3")))
                .singleElement()
                .satisfies(record -> assertThat(record.cause().suggestedAction())
                        .as("3/7 does not terminate, so there is no clean distribution to name")
                        .doesNotContain("per share of"));
    }

    @Test
    @DisplayName("a holding at another broker is not offered as the parent")
    void theCandidateHasToBeInTheSameAccount() {
        Ledger ledger = new Ledger();
        ledger.record(new OpeningBalance(JAN_15, com.basis.support.Fixtures.SCHWAB, "cash", "{}",
                Commodity.of(USD), qty("100000"), null));
        ledger.record(new Buy(JAN_15.plusDays(1), com.basis.support.Fixtures.SCHWAB, "buy", "{}",
                AAPL, qty("100"), Price.of("100.00", USD), usd("0.00")));

        BrokerSnapshot snapshot = new BrokerSnapshot(IBKR, AS_OF, SnapshotScope.SECURITIES_ONLY,
                List.of(reported(SPINCO, "25")));

        assertThat(new Reconciler(SplitCalendar.EMPTY).reconcile(ledger.state(), snapshot))
                .singleElement()
                .satisfies(record -> assertThat(record.cause().suggestedAction())
                        .as("shares at one broker are not explained by a position at another")
                        .doesNotContain("per share of"));
    }

    private static BrokerPosition reported(Commodity commodity, String quantity) {
        return BrokerPositions.held(IBKR, commodity, qty(quantity));
    }

    private static List<BreakRecord> reconcile(Quantity parentHeld, BrokerPosition alsoReported) {
        Ledger ledger = new Ledger();
        ledger.record(new OpeningBalance(JAN_15, IBKR, "cash", "{}",
                Commodity.of(USD), qty("100000"), null));
        ledger.record(new Buy(JAN_15.plusDays(1), IBKR, "buy", "{}", AAPL,
                parentHeld, Price.of("100.00", USD), usd("0.00")));

        BrokerSnapshot snapshot = new BrokerSnapshot(IBKR, AS_OF, SnapshotScope.SECURITIES_ONLY,
                List.of(BrokerPositions.held(IBKR, AAPL, parentHeld), alsoReported));

        return new Reconciler(SplitCalendar.EMPTY).reconcile(ledger.state(), snapshot);
    }
}
