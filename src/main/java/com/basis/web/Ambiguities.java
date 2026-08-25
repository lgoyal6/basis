package com.basis.web;

import com.basis.ledger.LedgerState;
import com.basis.reconcile.BreakRecord;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Breaks where basis knows something happened but not on what terms, offered as a choice.
 *
 * <p>The reconciler is deliberately unwilling to guess. When it finds a quantity ratio it
 * cannot back with evidence it says so and stops, which is right for a ledger and unhelpful
 * as an ending: the person looking at the screen often knows the answer, because it is on the
 * broker notice in their other tab.
 *
 * <p>So this turns a refusal into a question. Each entry carries the candidate readings basis
 * considers plausible, and choosing one applies it as an ordinary asserted corporate action,
 * through the same code path the {@code apply} command uses. Nothing here decides anything.
 * The user decides and the ledger records who decided.
 *
 * <p>Only offered for breaks whose shape is genuinely a ratio. A missing purchase is not an
 * ambiguity, it is a missing purchase, and presenting it as a choice between two corporate
 * actions would invite somebody to paper over a gap in their history with a split that never
 * happened.
 */
public final class Ambiguities {

    private Ambiguities() {
    }

    /**
     * One decision to put in front of somebody.
     *
     * @param prompt what basis saw, in plain words
     * @param symbol the holding in question
     * @param on the date the action would take effect
     * @param options the readings basis considers plausible, best first
     */
    public record Ambiguity(String prompt, String symbol, LocalDate on, List<Option> options) {

        public Ambiguity {
            options = List.copyOf(options);
        }
    }

    /**
     * One candidate reading.
     *
     * @param kind the corporate action to apply, matching what the ledger understands
     * @param detail the ratio, written new:old
     * @param label what to show on the button
     * @param consequence what applying it would do to the position, so the choice is informed
     */
    public record Option(String kind, String detail, String label, String consequence) {
    }

    /**
     * Turns the breaks a reconcile produced into the questions worth asking about them.
     *
     * <p>Takes the ledger state as well as the breaks, because a useful option says what it
     * would do to the actual position rather than restating the ratio the user can already
     * see.
     */
    public static List<Ambiguity> of(List<BreakRecord> breaks, LedgerState state) {
        List<Ambiguity> found = new ArrayList<>();
        for (BreakRecord record : breaks) {
            if (record.cause().confident()) {
                // Confirmed against real split history. There is nothing to choose: the
                // break already names the action, and offering alternatives would be
                // inventing doubt basis does not have.
                continue;
            }
            String code = record.cause().code();
            if (!code.equals("UNAPPLIED_SPLIT")
                    && !code.equals("UNAPPLIED_REVERSE_SPLIT")
                    && !code.equals("RATIO_WITHOUT_KNOWN_SPLIT")) {
                continue;
            }
            ratioOf(record).ifPresent(ratio -> found.add(ask(record, ratio)));
        }
        return List.copyOf(found);
    }

    private static Ambiguity ask(BreakRecord record, long[] ratio) {
        String symbol = record.commodity().symbol();
        LocalDate on = record.asOf();
        boolean brokerHasMore = record.brokerQuantity().compareTo(record.computedQuantity()) > 0;

        List<Option> options = new ArrayList<>();
        if (brokerHasMore) {
            options.add(new Option("split", ratio[0] + ":" + ratio[1],
                    "A " + ratio[0] + " for " + ratio[1] + " split",
                    "Restates every lot of " + symbol + " at " + ratio[0] + " times the shares"
                            + " and a " + ratio[1] + "/" + ratio[0] + " unit cost. The position is"
                            + " worth the same afterwards, and acquisition dates are kept."));
            options.add(new Option("", "", "Something is missing instead",
                    "Leaves the ledger alone. Choose this if you know the extra shares came from"
                            + " a purchase or a transfer that is not in the file you uploaded,"
                            + " because applying a split that did not happen would put a wrong"
                            + " cost basis on every share you own."));
        } else {
            // new:old, the same orientation "apply reverse-split" takes and the same
            // orientation the detected ratio already has. Swapping them here produced a
            // 3:1 "reverse" split, which the event correctly refuses as a split, and the
            // refusal surfaced as a 500 on a page the user could no longer load.
            options.add(new Option("reverse-split", ratio[0] + ":" + ratio[1],
                    "A " + ratio[0] + " for " + ratio[1] + " reverse split",
                    "Restates every lot of " + symbol + " at " + ratio[1] + " times the unit cost"
                            + " and " + ratio[0] + "/" + ratio[1] + " of the shares. Any fraction"
                            + " left over is usually sold for cash in lieu, which is a separate"
                            + " taxable event."));
            options.add(new Option("", "", "Something is missing instead",
                    "Leaves the ledger alone. Choose this if the shortfall is a sale or a"
                            + " transfer out that the uploaded history does not contain."));
        }

        String prompt = "Your broker reports " + record.brokerQuantity() + " " + symbol
                + " where basis computed " + record.computedQuantity()
                + ". That is exactly " + ratio[0] + " for " + ratio[1]
                + ", which is the shape of a corporate action, but nothing basis could check"
                + " confirms one. What actually happened?";
        return new Ambiguity(prompt, symbol, on, options);
    }

    /**
     * Pulls the ratio back out of the cause's own explanation.
     *
     * <p>Reading it from the sentence is not elegant. The alternative is widening
     * {@code ProbableCause} to carry a structured ratio, which would change a record the
     * ledger and the persistence layer both depend on, for the benefit of one screen. When a
     * second caller needs it, that is the moment to move it; until then the parsing lives
     * here, next to the only thing that wants it, and fails closed by offering no choice at
     * all if the wording ever changes.
     */
    private static java.util.Optional<long[]> ratioOf(BreakRecord record) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+) for (\\d+)")
                .matcher(record.cause().explanation());
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new long[] {
                    Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))});
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
