package com.basis.reference;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One ticker rename, as recorded by hand.
 *
 * <p>Hand maintained because the provider's symbol change endpoint is paywalled: week 0
 * probed it and got HTTP 402. That was recorded then as a known decision rather than a
 * week 5 surprise, and this is the file it was deferred to.
 *
 * @param effective the first date the new ticker was in use. A statement dated before this
 *     should still say the old one, which is what makes the date worth carrying rather
 *     than treating a rename as timeless.
 */
public record SymbolChange(String from, String to, LocalDate effective, String note) {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9][A-Z0-9.\\-]*");

    public SymbolChange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(effective, "effective");
        note = note == null ? "" : note.trim();
        from = from.trim().toUpperCase(java.util.Locale.ROOT);
        to = to.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SYMBOL.matcher(from).matches() || !SYMBOL.matcher(to).matches()) {
            throw new IllegalArgumentException("illegal ticker in rename " + from + " to " + to);
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("a ticker cannot be renamed to itself: " + from);
        }
    }
}
