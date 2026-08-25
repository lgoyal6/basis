package com.basis.importer;

import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How to read one broker's CSV export.
 *
 * <p>Everything that differs between brokers, and nothing that does not. Column names,
 * the words they use for a purchase, and the date formats they write are data; finding the
 * header, respecting quotes, and deciding what a purchase means to the ledger are code.
 * That split is what makes adding a broker an edit to a properties file.
 *
 * @param columns for each column basis needs, the header names a broker might use for it
 * @param actions for each thing a row can do, the phrases a broker might write for it
 */
public record BrokerProfile(
        String name,
        List<DateTimeFormatter> dateFormats,
        Map<String, List<String>> columns,
        Map<ActionKind, List<String>> actions) {

    /** The columns basis reads. A profile naming none of these for a required one is refused. */
    public static final List<String> COLUMNS =
            List.of("date", "action", "symbol", "description", "quantity", "price",
                    "commission", "fees", "amount");

    /** Without these a row cannot be understood at all. */
    public static final List<String> REQUIRED_COLUMNS = List.of("date", "action", "amount");

    public BrokerProfile {
        Objects.requireNonNull(name, "name");
        dateFormats = List.copyOf(dateFormats);
        columns = Map.copyOf(new LinkedHashMap<>(columns));
        actions = Map.copyOf(new EnumMap<>(actions));
        if (dateFormats.isEmpty()) {
            throw new IllegalArgumentException(name + " names no date formats");
        }
        for (String required : REQUIRED_COLUMNS) {
            if (columns.getOrDefault(required, List.of()).isEmpty()) {
                throw new IllegalArgumentException(name + " names no header for the " + required
                        + " column, and a row cannot be read without one");
            }
        }
        if (actions.values().stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException(name + " maps no action phrases, so every row would"
                    + " be unreadable");
        }
    }

    /** Header names this broker might use for a column basis needs. */
    public List<String> aliasesFor(String column) {
        return columns.getOrDefault(column, List.of());
    }

    /**
     * What a row's action text means, by longest matching prefix.
     *
     * <p>Prefix rather than equality because the real text continues past the verb:
     * "YOU BOUGHT PROSHARES ULTRAPRO QQQ". Longest wins, so a specific phrase beats a
     * general one that starts the same way and "DIVIDEND REINVESTMENT" is not read as a
     * plain dividend.
     */
    public Optional<ActionKind> classify(String action) {
        String text = action.toUpperCase(Locale.ROOT).trim();
        String bestPhrase = null;
        ActionKind best = null;
        for (Map.Entry<ActionKind, List<String>> entry : actions.entrySet()) {
            for (String phrase : entry.getValue()) {
                if (text.startsWith(phrase) && (bestPhrase == null || phrase.length() > bestPhrase.length())) {
                    bestPhrase = phrase;
                    best = entry.getKey();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Every phrase understood, so a failure can tell someone what to compare against. */
    public List<String> knownPhrases() {
        return actions.values().stream().flatMap(List::stream).sorted().toList();
    }
}
