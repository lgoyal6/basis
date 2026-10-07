package com.basis.documents.domain;

import java.math.BigDecimal;

public final class NumberNormalizer {
    private NumberNormalizer() { }
    public static BigDecimal parse(String raw, BigDecimal scale) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("numeric value is empty");
        String s = raw.trim().replace(",", "").replace("$", "");
        boolean negative = s.startsWith("(") && s.endsWith(")");
        if (negative) s = s.substring(1, s.length() - 1);
        s = s.replace("−", "-").trim();
        BigDecimal value = new BigDecimal(s).multiply(scale == null ? BigDecimal.ONE : scale);
        return negative ? value.negate() : value;
    }
}
