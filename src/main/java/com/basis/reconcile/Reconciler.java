package com.basis.reconcile;

import com.basis.domain.Account;
import com.basis.domain.Lot;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import com.basis.ledger.LedgerState;
import com.basis.ledger.PositionKey;
import com.basis.reference.SymbolMapping;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
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
    private final SymbolMapping renames;
    private final ExchangeRates rates;

    public Reconciler(SplitCalendar splits) {
        this(splits, SymbolMapping.empty(), ExchangeRates.NONE);
    }

    public Reconciler(SplitCalendar splits, SymbolMapping renames) {
        this(splits, renames, ExchangeRates.NONE);
    }

    public Reconciler(SplitCalendar splits, SymbolMapping renames, ExchangeRates rates) {
        this.splits = splits;
        this.renames = renames;
        this.rates = rates;
    }

    /** Reconciliation with no reference data: ratios are reported as suspicions only. */
    public static Reconciler withoutReferenceData() {
        return new Reconciler(SplitCalendar.EMPTY, SymbolMapping.empty());
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
        return collapseRenames(breaks, snapshot.asOf());
    }

    /**
     * Folds a rename's two halves into one break.
     *
     * <p>A security that changed ticker looks like two unrelated problems: a holding the
     * broker reports and the ledger has never heard of, and a holding the ledger has that
     * the broker has stopped reporting. Reported separately they send someone looking for a
     * missing purchase and a missing sale, neither of which happened. The rename file is
     * what turns them into one sentence.
     */
    private List<BreakRecord> collapseRenames(List<BreakRecord> breaks, LocalDate asOf) {
        if (renames.isEmpty()) {
            return List.copyOf(breaks);
        }
        List<BreakRecord> collapsed = new ArrayList<>();
        Set<BreakRecord> absorbed = new HashSet<>();

        for (BreakRecord unknownToLedger : breaks) {
            if (unknownToLedger.type() != BreakType.UNKNOWN_TO_LEDGER || absorbed.contains(unknownToLedger)) {
                continue;
            }
            for (BreakRecord staleHolding : breaks) {
                if (staleHolding.type() != BreakType.UNKNOWN_TO_BROKER || absorbed.contains(staleHolding)) {
                    continue;
                }
                if (!renames.renamedTo(staleHolding.commodity().symbol(), unknownToLedger.commodity().symbol())) {
                    continue;
                }
                absorbed.add(unknownToLedger);
                absorbed.add(staleHolding);
                collapsed.add(renameBreak(unknownToLedger, staleHolding, asOf));
                break;
            }
        }

        for (BreakRecord found : breaks) {
            if (!absorbed.contains(found)) {
                collapsed.add(found);
            }
        }
        collapsed.sort(Comparator.comparing((BreakRecord found) -> found.account().name())
                .thenComparing(found -> found.commodity().symbol()));
        return List.copyOf(collapsed);
    }

    private BreakRecord renameBreak(BreakRecord underNewName, BreakRecord underOldName, LocalDate asOf) {
        String oldSymbol = underOldName.commodity().symbol();
        String newSymbol = underNewName.commodity().symbol();
        String when = renames.lastChangeFor(oldSymbol)
                .map(change -> " on " + change.effective())
                .orElse("");
        boolean quantitiesAgree = underNewName.brokerQuantity().equals(underOldName.computedQuantity());

        String explanation = "The broker reports " + underNewName.brokerQuantity() + " " + newSymbol
                + " and basis holds " + underOldName.computedQuantity() + " " + oldSymbol + ". "
                + oldSymbol + " was renamed to " + newSymbol + when
                + ", so this is one position under two names"
                + (quantitiesAgree ? " and the counts agree." : ", though the counts still differ.");

        return new BreakRecord(asOf, underNewName.account(), underNewName.commodity(),
                BreakType.IDENTITY_MISMATCH,
                underNewName.brokerQuantity(), underOldName.computedQuantity(), null, null,
                ProbableCause.confirmed(ProbableCause.TICKER_RENAMED, explanation,
                        quantitiesAgree
                                ? "Rename " + oldSymbol + " to " + newSymbol + " in the imported history and"
                                        + " reconcile again."
                                : "Rename " + oldSymbol + " to " + newSymbol + " in the imported history, then"
                                        + " reconcile again to see what is left of the difference."),
                BreakStatus.OPEN);
    }

    private Optional<BreakRecord> compare(
            LedgerState state, LocalDate asOf, PositionKey key, BrokerPosition reported, Quantity computed) {

        if (reported == null) {
            return Optional.of(unknownToBroker(asOf, key, computed));
        }
        Quantity broker = reported.quantity();

        if (!broker.equals(computed)) {
            if (!computed.isPositive()) {
                return Optional.of(unknownToLedger(state, asOf, key, broker));
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

    /**
     * The cost the broker reports against the cost the ledger computed.
     *
     * <p>Three outcomes rather than one, because a currency the ledger does not hold the
     * position in is a different situation from a genuine difference in cost.
     *
     * <p>This method used to ask {@code openBasis} for the broker's currency and compare
     * whatever came back. That silently skips lots held in any other currency and returns
     * zero, so a holding bought in sterling and reported in dollars produced a break saying
     * the entire cost was missing, with a confident sentence explaining a difference that was
     * really just two currencies. A wrong answer stated plainly is worse than no answer.
     */
    private Optional<BreakRecord> basisMismatch(
            LedgerState state, LocalDate asOf, PositionKey key, BrokerPosition reported) {

        if (!reported.reportsBasis() || key.commodity().isCash()) {
            return Optional.empty();
        }
        Money brokerBasis = reported.reportedBasis();
        Currency brokerCurrency = brokerBasis.currency();
        Map<Currency, Money> held = state.openBasisByCurrency(key.account(), key.commodity());

        // The ordinary case: the position was bought in the currency the broker reports in.
        if (held.containsKey(brokerCurrency) && held.size() == 1) {
            Money computedBasis = held.get(brokerCurrency);
            if (brokerBasis.equals(computedBasis)) {
                return Optional.empty();
            }
            return Optional.of(basisBreak(asOf, key, reported, brokerBasis, computedBasis,
                    ProbableCause.suspected(ProbableCause.BASIS_DRIFT,
                            "The share count agrees but the cost does not: the broker says " + brokerBasis
                                    + " and basis computed " + computedBasis + ", a difference of "
                                    + brokerBasis.minus(computedBasis) + ".",
                            "Check whether commissions were capitalised into basis, or whether a corporate"
                                    + " action reallocated basis differently.")));
        }
        if (held.isEmpty()) {
            return Optional.empty();
        }
        return foreignBasis(asOf, key, reported, brokerBasis, held);
    }

    /**
     * A cost reported in a currency the position was not bought in.
     *
     * <p>Answers with a rate when one is available, and refuses when it is not. The rate is
     * named in the sentence along with the date it came from, because a converted number
     * nobody can reproduce is not evidence of anything.
     */
    private Optional<BreakRecord> foreignBasis(LocalDate asOf, PositionKey key,
            BrokerPosition reported, Money brokerBasis, Map<Currency, Money> held) {

        Currency brokerCurrency = brokerBasis.currency();
        Money converted = Money.zero(brokerCurrency);
        List<String> used = new ArrayList<>();
        for (Map.Entry<Currency, Money> entry : held.entrySet()) {
            if (entry.getKey().equals(brokerCurrency)) {
                converted = converted.plus(entry.getValue());
                continue;
            }
            Optional<ExchangeRates.Quote> quote = rates.rate(entry.getKey(), brokerCurrency, asOf);
            if (quote.isEmpty()) {
                return Optional.of(basisBreak(asOf, key, reported, brokerBasis, null,
                        ProbableCause.suspected(ProbableCause.CURRENCY_NOT_COMPARABLE,
                                "The broker reports a cost of " + brokerBasis + " but this position was"
                                        + " bought in " + describe(held) + ", and no "
                                        + entry.getKey().getCurrencyCode() + " to "
                                        + brokerCurrency.getCurrencyCode() + " rate is available for "
                                        + asOf + ". basis will not compare two currencies without one.",
                                "Fetch exchange rates with 'basis refresh-fx "
                                        + entry.getKey().getCurrencyCode()
                                        + brokerCurrency.getCurrencyCode() + "' and reconcile again.")));
            }
            ExchangeRates.Quote rate = quote.get();
            converted = converted.plus(Money.round(
                    entry.getValue().toMajorUnits().multiply(rate.rate()), brokerCurrency));
            used.add(entry.getKey().getCurrencyCode() + " at " + rate.rate().toPlainString()
                    + (rate.isStaleFor(asOf) ? " from " + rate.asOf() : ""));
        }

        if (brokerBasis.equals(converted)) {
            // Agrees once translated. Not a break, and saying nothing is the right answer:
            // the ledger and the broker are describing the same cost in different money.
            return Optional.empty();
        }
        return Optional.of(basisBreak(asOf, key, reported, brokerBasis, converted,
                ProbableCause.suspected(ProbableCause.FX_TRANSLATION,
                        "The broker reports " + brokerBasis + " and the position was bought in "
                                + describe(held) + ". Translated at " + String.join(", ", used)
                                + " that is " + converted + ", still a difference of "
                                + brokerBasis.minus(converted) + ".",
                        "Part of this is probably which rate and date your broker translated at."
                                + " basis keeps every lot in the currency it was bought in and"
                                + " converts only to compare, so the ledger itself is unaffected.")));
    }

    private static String describe(Map<Currency, Money> held) {
        List<String> parts = new ArrayList<>();
        held.forEach((currency, amount) -> parts.add(amount.toString()));
        return String.join(" plus ", parts);
    }

    private static BreakRecord basisBreak(LocalDate asOf, PositionKey key, BrokerPosition reported,
            Money brokerBasis, Money computedBasis, ProbableCause cause) {
        return new BreakRecord(asOf, key.account(), key.commodity(), BreakType.BASIS_MISMATCH,
                reported.quantity(), reported.quantity(), brokerBasis, computedBasis,
                cause, BreakStatus.OPEN);
    }

    /**
     * Whether an unexplained holding happens to be a clean fraction per share of something
     * held, which is the shape a spin off has.
     *
     * <p>Returns a sentence to add, never a classification. The first version of this had its
     * own cause code and displaced {@code UNKNOWN_HOLDING}, and it immediately explained a
     * coincidence as a corporate action: in the demo, 15 shares of one holding are exactly
     * 0.75 per share of another it has nothing to do with. A quantity ratio cannot tell a spin
     * off from a purchase nobody exported, and there is no corporate action feed here to
     * corroborate one, so the arithmetic stays arithmetic.
     */
    private static Optional<String> spinOffHint(
            LedgerState state, PositionKey key, Quantity broker) {

        if (key.commodity().isCash() || !broker.isPositive()) {
            return Optional.empty();
        }
        String accountRoot = parentAccountRoot(key.account());
        for (Lot lot : state.allLots()) {
            if (lot.commodity().equals(key.commodity())
                    || !lot.account().name().startsWith(accountRoot)) {
                continue;
            }
            Quantity parentHeld = state.position(lot.account(), lot.commodity());
            if (!parentHeld.isPositive()) {
                continue;
            }
            Optional<BigDecimal> perShare = distributionRatio(broker, parentHeld);
            if (perShare.isPresent()) {
                return Optional.of(" It is also exactly " + perShare.get().toPlainString()
                        + " per share of the " + parentHeld + " " + lot.commodity()
                        + " already held, so if " + lot.commodity() + " spun off "
                        + key.commodity() + ", apply the spin off with the basis fraction from"
                        + " the company's Form 8937. That ratio on its own is not evidence:"
                        + " unrelated holdings divide cleanly all the time, and basis will not"
                        + " guess which of the two happened.");
            }
        }
        return Optional.empty();
    }

    /**
     * The per parent share distribution, if it terminates at all.
     *
     * <p>Bounded to the range a real distribution falls in, and required to reproduce the
     * child quantity exactly. Neither test makes this evidence, which is why the caller only
     * ever produces a sentence.
     */
    private static Optional<BigDecimal> distributionRatio(Quantity child, Quantity parent) {
        BigDecimal ratio = child.value().divide(parent.value(), 6, RoundingMode.HALF_EVEN);
        if (ratio.compareTo(new BigDecimal("0.01")) < 0 || ratio.compareTo(new BigDecimal("5")) > 0) {
            return Optional.empty();
        }
        if (parent.value().multiply(ratio).compareTo(child.value()) != 0) {
            return Optional.empty();
        }
        return Optional.of(ratio.stripTrailingZeros());
    }

    /** The broker root, so a candidate parent is only looked for in the same account. */
    private static String parentAccountRoot(Account holdingAccount) {
        String name = holdingAccount.name();
        int lastSegment = name.lastIndexOf(':');
        return lastSegment < 0 ? name : name.substring(0, lastSegment);
    }

    private BreakRecord unknownToLedger(
            LedgerState state, LocalDate asOf, PositionKey key, Quantity broker) {
        String hint = spinOffHint(state, key, broker).orElse("");
        return new BreakRecord(asOf, key.account(), key.commodity(), BreakType.UNKNOWN_TO_LEDGER,
                broker, Quantity.ZERO, null, null,
                ProbableCause.suspected(ProbableCause.UNKNOWN_HOLDING,
                        "The broker reports " + broker + " " + key.commodity()
                                + " and the imported history contains no acquisition of it at all.",
                        "The statement covering the purchase is probably missing, or this security was"
                                + " renamed and the mapping file does not know about it yet." + hint),
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
