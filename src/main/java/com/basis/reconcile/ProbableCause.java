package com.basis.reconcile;

import java.util.Objects;

/**
 * Why basis thinks the two sides disagree.
 *
 * <p>The whole point of the product. "You have 30 fewer shares than expected" is a
 * subtraction anyone can do. "That is a 4 for 1 ratio, there is an unapplied split on
 * 2020-08-31, apply it?" is the thing worth building a ledger for.
 *
 * @param code a stable identifier for the kind of explanation, for grouping and metrics
 * @param explanation what basis believes happened, in a sentence a person can check
 * @param suggestedAction what to do about it, or empty when basis does not know
 * @param confident true only when something corroborates the arithmetic, such as a
 *     matching split in the reference data. Arithmetic alone is a suspicion.
 */
public record ProbableCause(String code, String explanation, String suggestedAction, boolean confident) {

    public static final String UNEXPLAINED = "UNEXPLAINED";
    public static final String UNAPPLIED_SPLIT = "UNAPPLIED_SPLIT";
    public static final String UNAPPLIED_REVERSE_SPLIT = "UNAPPLIED_REVERSE_SPLIT";
    public static final String MISSING_ACQUISITION = "MISSING_ACQUISITION";
    public static final String MISSING_DISPOSAL = "MISSING_DISPOSAL";
    public static final String UNKNOWN_HOLDING = "UNKNOWN_HOLDING";
    public static final String STALE_HOLDING = "STALE_HOLDING";
    public static final String BASIS_DRIFT = "BASIS_DRIFT";

    public ProbableCause {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(suggestedAction, "suggestedAction");
    }

    /** A cause with no suggested action, because basis has nothing useful to propose. */
    public static ProbableCause suspected(String code, String explanation) {
        return new ProbableCause(code, explanation, "", false);
    }

    public static ProbableCause suspected(String code, String explanation, String suggestedAction) {
        return new ProbableCause(code, explanation, suggestedAction, false);
    }

    /** A cause corroborated by something outside the arithmetic. */
    public static ProbableCause confirmed(String code, String explanation, String suggestedAction) {
        return new ProbableCause(code, explanation, suggestedAction, true);
    }

    /**
     * Honest about not knowing. A wrong explanation is worse than none: it sends someone
     * looking in the wrong place and it teaches them not to trust the next one.
     */
    public static ProbableCause unexplained(String detail) {
        return new ProbableCause(UNEXPLAINED, detail, "", false);
    }

    @Override
    public String toString() {
        return suggestedAction.isEmpty() ? explanation : explanation + " " + suggestedAction;
    }
}
