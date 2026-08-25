package com.basis.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of a broker statement, read but not yet understood.
 *
 * <p>Broker neutral on purpose. A parser's job ends here, at "these are the fields on the
 * page"; deciding that a row saying YOU BOUGHT is an acquisition is the mapper's job. Two
 * jobs rather than one, so that adding a second broker is a new parser and not a second
 * copy of the event logic.
 *
 * @param raw the line exactly as it appeared, which becomes {@code txn.source_row} and is
 *     kept forever. A parser bug is then fixable by replay rather than by asking someone
 *     for a statement they may no longer have.
 * @param ordinal the row's position in the file, one based. Fidelity's export carries no
 *     per row identifier, so this is what tells two otherwise identical fills apart.
 */
public record StatementRow(
        int ordinal,
        LocalDate date,
        String action,
        String symbol,
        String description,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal commission,
        BigDecimal fees,
        BigDecimal amount,
        String raw) {

    public StatementRow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(raw, "raw");
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        description = description == null ? "" : description.trim();
        action = action.trim();
    }

    public boolean hasSymbol() {
        return !symbol.isEmpty();
    }

    public Optional<BigDecimal> optionalQuantity() {
        return Optional.ofNullable(quantity);
    }

    /** Commission and other fees together, which is what the ledger charges as one expense. */
    public BigDecimal totalCharges() {
        return orZero(commission).add(orZero(fees));
    }

    public BigDecimal quantityOrZero() {
        return orZero(quantity);
    }

    public BigDecimal priceOrZero() {
        return orZero(price);
    }

    public BigDecimal amountOrZero() {
        return orZero(amount);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Where this row came from, for an error message someone has to act on. */
    public String location(String source) {
        return source + " row " + ordinal + " (" + date + " " + action + ")";
    }

    /**
     * The row as {@code txn.source_row} stores it.
     *
     * <p>{@code source_row} is JSONB and a CSV line is not JSON, so the line is carried
     * verbatim under {@code raw} inside an envelope that also says where it came from. The
     * parsed fields are deliberately not repeated here: the postings already state them, and
     * a second copy is a second thing that can disagree. What has to survive is the original
     * text, because that is what makes a parser bug fixable by replay.
     */
    public String asJson(String source) {
        return "{\"source\":" + quote(source)
                + ",\"ordinal\":" + ordinal
                + ",\"raw\":" + quote(raw) + "}";
    }

    /** Minimal JSON string escaping, so one CSV line cannot produce invalid JSON. */
    private static String quote(String text) {
        StringBuilder json = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
