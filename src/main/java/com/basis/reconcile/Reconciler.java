package com.basis.reconcile;

import com.basis.domain.Lot;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import com.basis.ledger.LedgerState;
import com.basis.ledger.PositionKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares what basis computed against what the broker reported, and explains the gaps.
 *
 * <p>Every disagreement becomes a {@link BreakRecord} with a {@link ProbableCause}. The
 * cause is the product. Reporting that a position is thirty shares short is a subtraction;
 * reporting that the broker holds exactly four times what the ledger does, and that there
 * is a 4 for 1 split on record that nobody applied, is the reason to have built a ledger.
 *
 * <p>Nothing here guesses. When the arithmetic suggests a ratio but no reference data
 * corroborates it, the cause says so and is marked not confident. When the numbers do not
 * form a ratio at all, the cause is that basis does not know, stated plainly, because a
 * confident wrong explanation sends someone looking in the wrong place and teaches them
 * not to trust the next one.
 */
public final class Reconciler {

    private final SplitCalendar splits;

    public Reconciler(SplitCalendar splits) {
        this.splits = splits;
    }

    /** Reconciliation with no reference data: ratios are reported as suspicions only. */
    public static Reconciler withoutReferenceData() {
        return new Reconciler(SplitCalendar.EMPTY);
    }

    /**
     * Every disagreement between the snapshot and the ledger, in account then commodity
     * order so two runs over the same inputs read the same way.
     */
    public List<BreakRecord> reconcile(LedgerState state, BrokerSnapshot snapshot) {
        Map<PositionKey, BrokerPosition> reported = new LinkedHashMap<>();
        for (BrokerPosition position : snapshot.positions()) {
            reported.put(new PositionKey(position.account(), position.commodity()), position);
        }

        Map<PositionKey, Quantity> computed = new LinkedHashMap<>();
        for (Map.Entry<PositionKey, Quantity> entry : state.positions().entrySet()) {
            if (entry.getKey().account().isUnder(snapshot.account())
                    && snapshot.scope().covers(entry.getKey().commodity())) {
                computed.put(entry.getKey(), entry.getValue());
            }
        }

        List<PositionKey> keys = new ArrayList<>(reported.keySet());
        for (PositionKey key : computed.keySet()) {
            if (!reported.containsKey(key)) {
                keys.add(key);
            }
        }
        keys.sort(null);

        List<BreakRecord> breaks = new ArrayList<>();
        for (PositionKey key : keys) {
            compare(state, snapshot.asOf(), key, reported.get(key), computed.getOrDefault(key, Quantity.ZERO))
                    .ifPresent(breaks::add);
        }
        return List.copyOf(breaks);
    }

    private Optional<BreakRecord> compare(
            LedgerState state, LocalDate asOf, PositionKey key, BrokerPosition reported, Quantity computed) {

        if (reported == null) {
            return Optional.of(unknownToBroker(asOf, key, computed));
        }
        Quantity broker = reported.quantity();

        if (!broker.equals(computed)) {
            if (!computed.isPositive()) {
                return Optional.of(unknownToLedger(asOf, key, broker));
            }
            return Optional.of(quantityMismatch(state, asOf, key, broker, computed));
        }
        return basisMismatch(state, asOf, key, reported);
    }

    private BreakRecord quantityMismatch(
            LedgerState state, LocalDate asOf, PositionKey key, Quantity broker, Quantity computed) {

        ProbableCause cause = key.commodity().isCash()
                ? cashCause(key, broker, computed)
                : quantityCause(state, asOf, key, broker, computed);
        return new BreakRecord(asOf, key.account(), key.commodity(), BreakType.QUANTITY_MISMATCH,
                broker, computed, null, null, cause, BreakStatus.OPEN);
    }

    /**
     * The heart of it. A clean whole number ratio between the two counts is what an
     * unapplied corporate action looks like from the outside.
     */
    private ProbableCause quantityCause(
            LedgerState state, LocalDate asOf, PositionKey key, Quantity broker, Quantity computed) {

        Optional<Ratio> ratio = RatioDetector.between(computed, broker);
        if (ratio.isEmpty()) {
            return missingActivity(key, broker, computed);
        }
        Ratio found = ratio.get();
        LocalDate from = earliestAcquisition(state, key).orElse(asOf);
        SplitCoverage coverage = splits.coverageBetween(key.commodity(), from, asOf);
        String splitKind = found.isIncrease() ? "split" : "reverse split";
        String code = found.isIncrease()
                ? ProbableCause.UNAPPLIED_SPLIT
                : ProbableCause.UNAPPLIED_REVERSE_SPLIT;

        Optional<KnownSplit> match = coverage.matching(found);
        if (match.isPresent()) {
            KnownSplit split = match.get();
            return ProbableCause.confirmed(code,
                    "The broker reports " + found + " of what basis computed, and " + key.commodity()
                            + " had a " + split.ratio() + " split effective " + split.date()
                            + " that this history never applied.",
                    "Apply the " + split.ratio() + " split of " + key.commodity() + " dated "
                            + split.date() + " and reconcile again.");
        }

        // Asked and answered. An empty split history from a provider that responded is
        // evidence, and it points away from a corporate action rather than towards one.
        if (coverage.isAuthoritative()) {
            return ProbableCause.suspected(ProbableCause.RATIO_WITHOUT_KNOWN_SPLIT,
                    "The broker reports " + found + " of what basis computed, which is the shape of an"
                            + " unapplied " + splitKind + ", but the split history for " + key.commodity()
                            + " was confirmed on " + coverage.checkedAt() + " and contains no such split"
                            + " between " + from + " and " + asOf + ". The ratio is a coincidence.",
                    "Treat this as missing trades rather than a corporate action, and look for activity"
                            + " the imported history does not contain.");
        }

        String why = coverage.status() == CoverageStatus.CHECK_FAILED
                ? "the last attempt to fetch it failed (" + coverage.detail() + ")"
                : "it has never been fetched";
        return ProbableCause.suspected(code,
                "The broker reports " + found + " of what basis computed, which is the shape of an unapplied "
                        + splitKind + ". The split history for " + key.commodity()
                        + " cannot confirm or rule that out because " + why
                        + ", so this is arithmetic and not evidence.",
                "Refresh the reference data for " + key.commodity() + " and reconcile again.");
    }

    private ProbableCause missingActivity(PositionKey key, Quantity broker, Quantity computed) {
        Quantity difference = broker.minus(computed);
        if (difference.isPositive()) {
            return ProbableCause.suspected(ProbableCause.MISSING_ACQUISITION,
                    "The broker reports " + difference + " more " + key.commodity()
                            + " than basis computed, and the two counts are not a ratio, so this is not"
                            + " a corporate action.",
                    "Look for a purchase, a transfer in or a reinvested dividend missing from the imported"
                            + " history.");
        }
        return ProbableCause.suspected(ProbableCause.MISSING_DISPOSAL,
                "basis computed " + difference.negate() + " more " + key.commodity()
                        + " than the broker reports, and the two counts are not a ratio.",
                "Look for a sale, a transfer out or a corporate action missing from the imported history.");
    }

    /**
     * Cash gets no ratio detector. A cash balance that is twice another cash balance means
     * nothing at all, whereas a share count that is twice another is a split, and running
     * the same detector over both would manufacture confident nonsense.
     */
    private ProbableCause cashCause(PositionKey key, Quantity broker, Quantity computed) {
        Money difference = Money.of(broker.minus(computed).value(), key.commodity().asCurrency());
        if (difference.isNegative()) {
            return ProbableCause.suspected(ProbableCause.UNEXPLAINED,
                    "The broker holds " + difference.negate() + " less cash than basis computed.",
                    "Look for a fee, a tax withholding or an interest charge that the imported history"
                            + " does not contain.");
        }
        return ProbableCause.suspected(ProbableCause.UNEXPLAINED,
                "The broker holds " + difference + " more cash than basis computed.",
                "Look for interest, a distribution or a deposit that the imported history does not contain.");
    }

    private Optional<BreakRecord> basisMismatch(
            LedgerState state, LocalDate asOf, PositionKey key, BrokerPosition reported) {

        if (!reported.reportsBasis() || key.commodity().isCash()) {
            return Optional.empty();
        }
        Money brokerBasis = reported.reportedBasis();
        Money computedBasis = state.openBasis(key.account(), key.commodity(), brokerBasis.currency());
        if (brokerBasis.equals(computedBasis)) {
            return Optional.empty();
        }

        Money difference = brokerBasis.minus(computedBasis);
        return Optional.of(new BreakRecord(asOf, key.account(), key.commodity(), BreakType.BASIS_MISMATCH,
                reported.quantity(), reported.quantity(), brokerBasis, computedBasis,
                ProbableCause.suspected(ProbableCause.BASIS_DRIFT,
                        "The share count agrees but the cost does not: the broker says " + brokerBasis
                                + " and basis computed " + computedBasis + ", a difference of " + difference + ".",
                        "Check whether commissions were capitalised into basis, or whether a corporate action"
                                + " reallocated basis differently."),
                BreakStatus.OPEN));
    }

    private BreakRecord unknownToLedger(LocalDate asOf, PositionKey key, Quantity broker) {
        return new BreakRecord(asOf, key.account(), key.commodity(), BreakType.UNKNOWN_TO_LEDGER,
                broker, Quantity.ZERO, null, null,
                ProbableCause.suspected(ProbableCause.UNKNOWN_HOLDING,
                        "The broker reports " + broker + " " + key.commodity()
                                + " and the imported history contains no acquisition of it at all.",
                        "The statement covering the purchase is probably missing, or this security was"
                                + " renamed and the mapping file does not know about it yet."),
                BreakStatus.OPEN);
    }

    private BreakRecord unknownToBroker(LocalDate asOf, PositionKey key, Quantity computed) {
        return new BreakRecord(asOf, key.account(), key.commodity(), BreakType.UNKNOWN_TO_BROKER,
                Quantity.ZERO, computed, null, null,
                ProbableCause.suspected(ProbableCause.STALE_HOLDING,
                        "basis computed " + computed + " " + key.commodity()
                                + " and the broker does not report the position at all.",
                        "Look for a disposal, a transfer out, or a delisting missing from the imported"
                                + " history."),
                BreakStatus.OPEN);
    }

    private static Optional<LocalDate> earliestAcquisition(LedgerState state, PositionKey key) {
        return state.openLots(key.account(), key.commodity()).stream()
                .map(Lot::acquisitionDate)
                .min(LocalDate::compareTo);
    }
}
