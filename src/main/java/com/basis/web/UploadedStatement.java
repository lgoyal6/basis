package com.basis.web;

import com.basis.domain.Account;
import java.util.List;

/**
 * What somebody uploaded, kept exactly as they sent it.
 *
 * <p>The lines are the file, verbatim. Nothing is reformatted or cleaned on the way in,
 * because the whole ledger already treats the source row as the thing of record and a
 * statement that was tidied before anyone looked at it cannot be argued with later.
 *
 * <p>Holds no derived state. Breaks, positions and lots are recomputed from these lines on
 * every request, which costs a few milliseconds and buys two things: the delete button has
 * only one place to delete from, and there is no cached answer that can disagree with what
 * the ledger would say now.
 */
public record UploadedStatement(
        String broker,
        Account account,
        List<String> historyLines,
        List<String> positionLines,
        String historyFilename,
        String positionFilename,
        boolean demo,
        List<AppliedChoice> choices) {

    /** The account name used for anything uploaded. Nobody is asked to invent one. */
    public static final Account ACCOUNT = Account.of("Assets:Broker:Uploaded");

    public UploadedStatement {
        historyLines = List.copyOf(historyLines);
        positionLines = positionLines == null ? List.of() : List.copyOf(positionLines);
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static UploadedStatement of(String broker, List<String> historyLines,
            List<String> positionLines, String historyFilename, String positionFilename,
            boolean demo) {
        return new UploadedStatement(broker, ACCOUNT, historyLines, positionLines,
                historyFilename, positionFilename, demo, List.of());
    }

    public boolean hasPositions() {
        return !positionLines.isEmpty();
    }

    /** The same statement with one more corporate action decision recorded against it. */
    public UploadedStatement plus(AppliedChoice choice) {
        List<AppliedChoice> next = new java.util.ArrayList<>(choices);
        next.removeIf(existing -> existing.sameSubjectAs(choice));
        next.add(choice);
        return new UploadedStatement(broker, account, historyLines, positionLines,
                historyFilename, positionFilename, demo, next);
    }

    /**
     * A corporate action the user resolved.
     *
     * <p>Kept beside the statement rather than folded into it, so the uploaded file stays
     * the uploaded file. Replaying applies the statement first and then these, in the order
     * they were decided, which is also the order that actually happened.
     */
    public record AppliedChoice(String kind, String symbol, String detail, java.time.LocalDate on) {

        boolean sameSubjectAs(AppliedChoice other) {
            return kind.equals(other.kind) && symbol.equals(other.symbol) && on.equals(other.on);
        }
    }
}
