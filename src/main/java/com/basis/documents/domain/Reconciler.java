package com.basis.documents.domain;

import java.math.BigDecimal;

/** Deterministic comparison. A model may suggest labels elsewhere, never a value or result. */
public final class Reconciler {
    private static final int REVIEW_THRESHOLD = 800;
    private Reconciler() { }

    public static ReconciliationResult compare(NormalizedFact left, NormalizedFact right) {
        if (left.issuer() == null || right.issuer() == null || left.value() == null || right.value() == null
                || (left.unit() == FactUnit.MONETARY && left.currency() == null)
                || (right.unit() == FactUnit.MONETARY && right.currency() == null)) {
            return result(ReconciliationClass.UNSUPPORTED_COMPARISON, left, right, "a required comparison key is missing");
        }
        if (!left.periodEnd().equals(right.periodEnd()) || !same(left.periodStart(), right.periodStart()))
            return result(ReconciliationClass.PERIOD_MISMATCH, left, right, "reporting periods differ");
        if (left.unit() != right.unit() || !same(left.currency(), right.currency()))
            return result(ReconciliationClass.UNIT_MISMATCH, left, right, "units or currencies differ");
        if (left.value().compareTo(right.value()) == 0 && left.rawValue().equals(right.rawValue()))
            return result(ReconciliationClass.EXACT_MATCH, left, right, "raw and normalized values match");
        if (left.value().compareTo(right.value()) == 0)
            return result(ReconciliationClass.EQUIVALENT_AFTER_NORMALIZATION, left, right, "normalized values match");
        if (left.confidencePermille() < REVIEW_THRESHOLD || right.confidencePermille() < REVIEW_THRESHOLD)
            return result(ReconciliationClass.LOW_CONFIDENCE_MATCH, left, right, "values differ and at least one source is below review confidence");
        return result(ReconciliationClass.CONFLICTING_VALUE, left, right, "normalized values differ");
    }

    private static boolean same(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private static ReconciliationResult result(ReconciliationClass c, NormalizedFact a, NormalizedFact b, String rule) {
        return new ReconciliationResult(c, rule + "; left=" + a.rawValue() + " @ " + a.location() + ", right=" + b.rawValue() + " @ " + b.location());
    }
}
