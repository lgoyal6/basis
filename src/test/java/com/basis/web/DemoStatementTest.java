package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.reconcile.KnownSplit;
import com.basis.reconcile.SplitCalendar;
import com.basis.reconcile.SplitCoverage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The demo has one job: show somebody the interesting outcomes before they trust it with
 * anything. A demo that reconciles cleanly, or that only ever finds one kind of problem,
 * fails at that quietly.
 */
class DemoStatementTest {

    /**
     * Deliberately given a calendar that knows nothing.
     *
     * <p>The demo carries its own split history, so it has to produce a confirmed break even
     * when the server's reference cache is empty. That is not hypothetical: the first time
     * this ran against a real server the cache was empty, every break came back "suspected",
     * and the demo silently lost the one thing it exists to show.
     */
    private final BreakFinder finder = new BreakFinder(SplitCalendar.EMPTY);

    @Test
    @DisplayName("the demo parses and reconciles without anybody uploading anything")
    void theDemoRuns() {
        BreakFinder.Result result = finder.find(new DemoStatement().build());

        assertThat(result.reconciled()).isTrue();
        assertThat(result.rowsRead()).isGreaterThan(5);
        assertThat(result.breaks()).as("a clean demo would demonstrate nothing").isNotEmpty();
    }

    @Test
    @DisplayName("it shows a break it can prove, one it refuses to guess at, and one that is simply missing")
    void itCoversTheThreeKindsOfAnswer() {
        BreakFinder.Result result = finder.find(new DemoStatement().build());
        List<String> causes = result.breaks().stream()
                .map(record -> record.cause().code()).distinct().toList();

        assertThat(result.confirmedCount())
                .as("at least one break with real evidence behind it")
                .isGreaterThan(0);
        assertThat(result.breaks()).anySatisfy(record ->
                assertThat(record.cause().confident())
                        .as("and at least one it will not claim to be sure about")
                        .isFalse());
        assertThat(causes)
                .as("more than one kind of finding, or the classifier looks like a one trick pony")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("the confirmed Apple split leads the list and offers no choice, because it is known")
    void theProvableOneComesFirst() {
        BreakFinder.Result result = finder.find(new DemoStatement().build());

        assertThat(result.breaks().get(0)).satisfies(first -> {
            assertThat(first.commodity().symbol()).isEqualTo("AAPL");
            assertThat(first.cause().code()).isEqualTo("UNAPPLIED_SPLIT");
            assertThat(first.cause().confident()).isTrue();
        });
        assertThat(result.ambiguities())
                .as("the Apple one is proven, so no question is asked about it")
                .noneSatisfy(question -> assertThat(question.symbol()).isEqualTo("AAPL"));
    }

    @Test
    @DisplayName("a holding the broker reports and the history never mentions is its own finding")
    void anUnknownHoldingIsNotCalledASplit() {
        BreakFinder.Result result = finder.find(new DemoStatement().build());

        assertThat(result.breaks()).anySatisfy(record -> {
            assertThat(record.commodity().symbol()).isEqualTo("TSLA");
            assertThat(record.cause().code())
                    .as("nothing was bought, so this is missing history and not a corporate action")
                    .isIn("UNKNOWN_HOLDING", "MISSING_ACQUISITION");
        });
    }

    @Test
    @DisplayName("something that agrees is included, so the demo is not all bad news")
    void notEverythingIsBroken() {
        BreakFinder.Result result = finder.find(new DemoStatement().build());

        assertThat(result.breaks())
                .as("MSFT is set up to reconcile, which is what shows there are no false positives")
                .noneSatisfy(record -> {
                    assertThat(record.commodity().symbol()).isEqualTo("MSFT");
                    assertThat(record.type().name()).isEqualTo("QUANTITY_MISMATCH");
                });
    }

    @Test
    @DisplayName("choosing the offered action on a fund actually clears that fund's break")
    void aChoiceOnAFundIsAppliedToTheFund() {
        // VTSAX is declared a mutual fund in the commodity catalog. The choice used to build
        // Commodity.equity(symbol), which is a different commodity, so the corporate action
        // restated nothing and the break survived being fixed. Nothing errored, which is what
        // made it worth a test: the page just quietly did not work.
        UploadedStatement demo = new DemoStatement().build();
        BreakFinder.Result before = finder.find(demo);

        Ambiguities.Ambiguity question = before.ambiguities().stream()
                .filter(a -> a.symbol().equals("VTSAX")).findFirst().orElseThrow();
        Ambiguities.Option chosen = question.options().get(0);

        BreakFinder.Result after = finder.find(demo.plus(new UploadedStatement.AppliedChoice(
                chosen.kind(), "VTSAX", chosen.detail(), question.on())));

        assertThat(after.breaks())
                .as("VTSAX should no longer disagree once its reverse split is applied")
                .noneSatisfy(record -> assertThat(record.commodity().symbol()).isEqualTo("VTSAX"));
        assertThat(after.breaks().size())
                .as("and only that one should have been fixed")
                .isEqualTo(before.breaks().size() - 1);
    }

    @Test
    @DisplayName("the demo is marked as a demo, so its numbers never join the real ones")
    void demoSessionsAreLabelled() {
        assertThat(new DemoStatement().build().demo()).isTrue();
    }
}
