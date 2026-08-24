package com.basis.ledger;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.buy;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.event.Split;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Invariant 8, corporate action value preservation. Deliberately failing.
 *
 * <p>A split changes the share count and the per share cost basis without changing the
 * total basis or the total value of the position. A 4 for 1 split of 10 shares at 150.00
 * has to become 40 shares at 37.50, with the position's basis unchanged at 1500.00 and
 * every lot's acquisition date preserved, because the holding period did not restart.
 *
 * <p>Corporate actions are week 3 work. This test fails rather than being disabled, on
 * purpose: a skipped test is a green tick over a hole, and the whole argument for this
 * ledger is that it does not hide what it cannot yet do. Expect exactly one failing test
 * at the end of week 1. Anything else red is a real regression.
 *
 * <p>See docs/ARCHITECTURE.md section 11.
 */
@Tag("week3")
class CorporateActionValuePreservationTest {

    @Test
    @DisplayName("invariant 8, a split preserves total basis and total value: WEEK 3, NOT IMPLEMENTED")
    void splitPreservesTotalBasisAndValue() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "0.00"));

        Split split = new Split(JAN_15.plusMonths(1), IBKR, "ca1", "{\"ratio\":\"4:1\"}", AAPL, 4, 1);

        // When this is implemented, the assertions below become the real ones:
        //   position becomes 40 shares
        //   the lot's unit cost becomes 37.50
        //   total basis is still 1500.00
        //   the acquisition date is still JAN_15, because the holding period did not restart
        //   the transaction still balances at cost, with no gain realized
        assertThat(handled(ledger, split))
                .as("invariant 8 is week 3 work. Corporate actions are declared but not handled,"
                        + " so a split cannot yet be applied and total basis cannot yet be shown"
                        + " to survive one. This test is expected to fail until week 3.")
                .isTrue();
    }

    private static boolean handled(Ledger ledger, Split split) {
        try {
            ledger.record(split);
            return true;
        } catch (UnsupportedOperationException expectedUntilWeek3) {
            return false;
        }
    }
}
