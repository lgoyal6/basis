package com.basis.reconcile;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.MSFT;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.buy;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.ledger.Ledger;
import com.basis.ledger.LedgerAccounts;
import com.basis.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation, and the explanations it attaches.
 *
 * <p>The first test is the one the README promises: not "you have 30 fewer shares than
 * expected" but "that is a 4 for 1 ratio, there is an unapplied split on this date".
 */
class ReconcilerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);

    @Test
    @DisplayName("a broker holding four times as much reads as an unapplied split, not as missing shares")
    void ratioIsReportedAsASplitRatherThanAsMissingShares() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("40"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.type()).isEqualTo(BreakType.QUANTITY_MISMATCH);
            assertThat(found.brokerQuantity()).isEqualTo(qty("40"));
            assertThat(found.computedQuantity()).isEqualTo(qty("10"));
            assertThat(found.quantityDifference()).isEqualTo(qty("30"));
            assertThat(found.cause().code()).isEqualTo(ProbableCause.UNAPPLIED_SPLIT);
            assertThat(found.cause().explanation()).contains("4 for 1");
            assertThat(found.cause().explanation())
                    .as("says why it cannot tell, rather than implying it checked")
                    .contains("never been fetched");
            assertThat(found.cause().suggestedAction()).contains("Refresh the reference data");
            assertThat(found.cause().confident())
                    .as("nothing corroborates the arithmetic yet, so this is a suspicion")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("with the split on record, the same break names the date and becomes a finding")
    void referenceDataTurnsTheSuspicionIntoAFinding() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        SplitCalendar calendar = calendarWith(
                new KnownSplit(AAPL, LocalDate.of(2026, 2, 20), 4, 1, Instant.EPOCH));

        List<BreakRecord> breaks = new Reconciler(calendar)
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("40"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().confident()).isTrue();
            assertThat(found.cause().explanation()).contains("2026-02-20");
            assertThat(found.cause().suggestedAction())
                    .contains("Apply the 4 for 1 split")
                    .contains("2026-02-20");
        });
    }

    @Test
    @DisplayName("a split on record with the wrong ratio does not get credited for the break")
    void aMismatchedSplitDoesNotExplainTheBreak() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        SplitCalendar calendar = calendarWith(
                new KnownSplit(AAPL, LocalDate.of(2026, 2, 20), 2, 1, Instant.EPOCH));

        List<BreakRecord> breaks = new Reconciler(calendar)
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("40"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().confident())
                    .as("a 2 for 1 on record does not explain a 4 for 1 gap")
                    .isFalse();
            assertThat(found.cause().code())
                    .as("the provider answered, so the ratio is provably not a split")
                    .isEqualTo(ProbableCause.RATIO_WITHOUT_KNOWN_SPLIT);
        });
    }

    @Test
    @DisplayName("a split outside the window the position could have been affected by is ignored")
    void aSplitBeforeTheHoldingExistedIsIgnored() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        SplitCalendar calendar = calendarWith(
                new KnownSplit(AAPL, JAN_15.minusYears(3), 4, 1, Instant.EPOCH));

        List<BreakRecord> breaks = new Reconciler(calendar)
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("40"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().confident())
                    .as("a split from before the shares were bought cannot be the unapplied one")
                    .isFalse();
            assertThat(found.cause().code()).isEqualTo(ProbableCause.RATIO_WITHOUT_KNOWN_SPLIT);
        });
    }

    @Test
    @DisplayName("the broker holding a fraction of the computed count reads as a reverse split")
    void reverseRatioReadsAsAReverseSplit() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "80", "1.00", "0.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("10"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().code()).isEqualTo(ProbableCause.UNAPPLIED_REVERSE_SPLIT);
            assertThat(found.cause().explanation()).contains("1 for 8");
        });
    }

    @Test
    @DisplayName("a difference that is not a ratio is reported as missing activity, not as a split")
    void nonRatioDifferenceIsNotBlamedOnACorporateAction() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("137"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().code()).isEqualTo(ProbableCause.MISSING_ACQUISITION);
            assertThat(found.cause().explanation()).contains("not a ratio");
            assertThat(found.cause().suggestedAction()).contains("purchase");
        });
    }

    @Test
    @DisplayName("the ledger holding more, with no ratio, reads as a missing disposal")
    void computedExcessReadsAsAMissingDisposal() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "137", "10.00", "0.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("100"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().code()).isEqualTo(ProbableCause.MISSING_DISPOSAL);
            assertThat(found.cause().suggestedAction()).contains("sale");
        });
    }

    @Test
    @DisplayName("agreement produces no break at all")
    void agreementIsSilent() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        assertThat(Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("10")))))
                .isEmpty();
    }

    @Test
    @DisplayName("a position the broker reports and the ledger never saw is its own kind of break")
    void unknownToLedger() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData().reconcile(ledger.state(),
                snapshot(BrokerPositions.held(IBKR, AAPL, qty("10")),
                        BrokerPositions.held(IBKR, MSFT, qty("25"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.type()).isEqualTo(BreakType.UNKNOWN_TO_LEDGER);
            assertThat(found.commodity()).isEqualTo(MSFT);
            assertThat(found.computedQuantity()).isEqualTo(com.basis.domain.Quantity.ZERO);
            assertThat(found.cause().code()).isEqualTo(ProbableCause.UNKNOWN_HOLDING);
            assertThat(found.cause().suggestedAction()).contains("renamed");
        });
    }

    @Test
    @DisplayName("a position the ledger holds and the broker does not report is the other kind")
    void unknownToBroker() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledger.record(buy(JAN_15, "b2", MSFT, "25", "300.00", "0.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("10"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.type()).isEqualTo(BreakType.UNKNOWN_TO_BROKER);
            assertThat(found.commodity()).isEqualTo(MSFT);
            assertThat(found.brokerQuantity()).isEqualTo(com.basis.domain.Quantity.ZERO);
            assertThat(found.cause().code()).isEqualTo(ProbableCause.STALE_HOLDING);
        });
    }

    @Test
    @DisplayName("agreeing on the count and disagreeing on the cost is a basis break")
    void basisMismatchIsReportedSeparately() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "1.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData().reconcile(ledger.state(),
                snapshot(BrokerPositions.held(IBKR, AAPL, qty("10"), usd("1501.00"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.type()).isEqualTo(BreakType.BASIS_MISMATCH);
            assertThat(found.brokerAmount()).isEqualTo(usd("1501.00"));
            assertThat(found.computedAmount())
                    .as("basis expenses the commission rather than capitalising it")
                    .isEqualTo(usd("1500.00"));
            assertThat(found.cause().code()).isEqualTo(ProbableCause.BASIS_DRIFT);
            assertThat(found.cause().suggestedAction()).contains("commissions were capitalised");
        });
    }

    @Test
    @DisplayName("a statement that reports no cost basis raises no basis break")
    void noReportedBasisMeansNoBasisBreak() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "1.00"));

        assertThat(Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("10")))))
                .as("most statements report quantity and market value and nothing else")
                .isEmpty();
    }

    @Test
    @DisplayName("cash is compared but never blamed on a split")
    void cashIsNotRunThroughTheRatioDetector() {
        Ledger ledger = new Ledger();
        ledger.record(Fixtures.openingCash(JAN_15, "o1", "1000.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData().reconcile(ledger.state(),
                BrokerSnapshot.complete(IBKR, AS_OF, List.of(BrokerPositions.cash(IBKR, usd("500.00")))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.commodity().isCash()).isTrue();
            assertThat(found.cause().code())
                    .as("a cash balance that is half another cash balance is not a reverse split")
                    .isEqualTo(ProbableCause.UNEXPLAINED);
            assertThat(found.cause().explanation()).contains("500.00 USD less cash");
            assertThat(found.cause().suggestedAction()).contains("fee");
        });
    }

    @Test
    @DisplayName("a real history that applied its split reconciles clean")
    void anAppliedSplitLeavesNothingToExplain() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledger.record(new com.basis.domain.event.Split(
                LocalDate.of(2026, 2, 20), IBKR, "ca1", "{}", AAPL, 4, 1));

        assertThat(Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("40")))))
                .as("the split was applied, so basis and the broker now agree")
                .isEmpty();
    }

    @Test
    @DisplayName("breaks come back in a stable order, so two runs read the same")
    void breaksAreOrdered() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledger.record(buy(JAN_15, "b2", MSFT, "10", "300.00", "0.00"));

        List<BreakRecord> breaks = Reconciler.withoutReferenceData().reconcile(ledger.state(),
                snapshot(BrokerPositions.held(IBKR, MSFT, qty("20")),
                        BrokerPositions.held(IBKR, AAPL, qty("20"))));

        assertThat(breaks).extracting(record -> record.commodity().symbol())
                .containsExactly("AAPL", "MSFT");
    }

    @Test
    @DisplayName("a sale that closed a position agrees with a broker that no longer reports it")
    void aClosedPositionIsNotABreak() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledger.record(Fixtures.sell(JAN_15.plusDays(5), "s1", AAPL, "10", "160.00", "0.00",
                LotSelectionMethod.FIFO));

        assertThat(Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), snapshot()))
                .isEmpty();
    }

    @Test
    @DisplayName("a provider that answered and found nothing rules the split out, rather than staying silent")
    void anAnsweredEmptyCalendarIsEvidence() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        SplitCalendar answeredWithNothing = calendarWith();

        List<BreakRecord> breaks = new Reconciler(answeredWithNothing)
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("40"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().code()).isEqualTo(ProbableCause.RATIO_WITHOUT_KNOWN_SPLIT);
            assertThat(found.cause().explanation())
                    .contains("was confirmed on")
                    .contains("The ratio is a coincidence");
            assertThat(found.cause().suggestedAction())
                    .as("points at missing trades, not at fetching data that has already been fetched")
                    .contains("missing trades");
        });
    }

    @Test
    @DisplayName("a provider that could not be reached says so, and does not rule anything out")
    void aFailedCheckIsNotEvidence() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        SplitCalendar refused = (commodity, from, to) ->
                SplitCoverage.checkFailed("NOT_AVAILABLE [HTTP 402]", Instant.parse("2026-03-30T00:00:00Z"));

        List<BreakRecord> breaks = new Reconciler(refused)
                .reconcile(ledger.state(), snapshot(BrokerPositions.held(IBKR, AAPL, qty("40"))));

        assertThat(breaks).singleElement().satisfies(found -> {
            assertThat(found.cause().code())
                    .as("a failed check cannot rule a split out, so the suspicion stands")
                    .isEqualTo(ProbableCause.UNAPPLIED_SPLIT);
            assertThat(found.cause().confident()).isFalse();
            assertThat(found.cause().explanation())
                    .contains("the last attempt to fetch it failed")
                    .contains("HTTP 402");
        });
    }

    @Test
    @DisplayName("coverage that never checked cannot pretend to carry splits")
    void unavailableCoverageCannotCarrySplits() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SplitCoverage(
                CoverageStatus.NEVER_CHECKED,
                List.of(new KnownSplit(AAPL, LocalDate.of(2026, 2, 20), 4, 1, Instant.EPOCH)),
                null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot carry splits");
    }

    private static BrokerSnapshot snapshot(BrokerPosition... positions) {
        return BrokerSnapshot.ofSecurities(IBKR, AS_OF, List.of(positions));
    }

    /** A calendar that answered, and knows about these splits. */
    private static SplitCalendar calendarWith(KnownSplit... known) {
        return (commodity, from, to) -> SplitCoverage.checked(
                List.of(known).stream()
                        .filter(split -> split.commodity().equals(commodity))
                        .filter(split -> !split.date().isBefore(from) && !split.date().isAfter(to))
                        .toList(),
                Instant.parse("2026-03-30T00:00:00Z"));
    }

    @Test
    @DisplayName("the cash account convention is the ledger's, not reinvented here")
    void cashPositionUsesTheLedgerConvention() {
        assertThat(BrokerPositions.cash(IBKR, usd("100.00")).account())
                .isEqualTo(LedgerAccounts.cash(IBKR));
        assertThat(BrokerPositions.held(IBKR, AAPL, qty("1")).account())
                .isEqualTo(LedgerAccounts.holding(IBKR, AAPL));
        assertThat(BrokerPositions.cash(IBKR, usd("100.00")).commodity())
                .isEqualTo(Commodity.of(USD));
    }
}
