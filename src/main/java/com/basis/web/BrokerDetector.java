package com.basis.web;

import com.basis.importer.BrokerProfile;
import com.basis.importer.BrokerProfiles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Works out which broker wrote a file, by asking every profile how much of the header it
 * recognises.
 *
 * <p>Nobody uploading a statement for the first time knows or cares what basis calls their
 * broker's format, and a dropdown they have to get right before seeing anything is a reason
 * to close the tab. So the file is asked instead of the person.
 *
 * <p>Scored rather than matched. A profile earns a point for each column it can find, and
 * the highest score wins. That is deliberately loose: brokers rename columns, add columns,
 * and export different subsets from different screens, and a detector that demanded an exact
 * header would reject files the importer can read perfectly well.
 *
 * <p>It can be wrong, so the answer is always overridable and the page says which broker it
 * picked. A detector presented as certain is worse than a dropdown; one presented as a guess
 * with a visible override is better than both.
 */
public final class BrokerDetector {

    /** How much of a header a profile has to recognise before it is worth offering. */
    private static final int MINIMUM_COLUMNS = 3;

    /** The columns that actually distinguish a statement, weighted by how telling they are. */
    private static final List<String> SIGNALS =
            List.of("date", "action", "symbol", "quantity", "amount", "price");

    private BrokerDetector() {
    }

    public record Guess(String broker, String displayName, int matched, int total) {

        /** True when enough of the header was recognised to be worth acting on. */
        public boolean isUsable() {
            return matched >= MINIMUM_COLUMNS;
        }

        public int percent() {
            return total == 0 ? 0 : matched * 100 / total;
        }
    }

    /**
     * Every broker that could plausibly have written this header, best first.
     *
     * <p>Returns the list rather than one answer so the page can offer the runners up. When
     * the top guess is wrong, the right one is usually second, and picking from two named
     * candidates is a much smaller ask than choosing from a list of every broker.
     */
    public static List<Guess> rank(String headerLine) {
        List<String> header = splitHeader(headerLine);
        List<Guess> guesses = new ArrayList<>();
        for (String broker : BrokerProfiles.available()) {
            BrokerProfile profile = BrokerProfiles.load(broker);
            int matched = 0;
            for (String column : SIGNALS) {
                if (headerContainsAny(header, profile.aliasesFor(column))) {
                    matched++;
                }
            }
            guesses.add(new Guess(broker, profile.name(), matched, SIGNALS.size()));
        }
        guesses.sort(Comparator.comparingInt(Guess::matched).reversed()
                .thenComparing(Guess::broker));
        return List.copyOf(guesses);
    }

    /** The single best guess, if any profile recognised enough of the header to be worth it. */
    public static Optional<Guess> detect(String headerLine) {
        return rank(headerLine).stream().filter(Guess::isUsable).findFirst();
    }

    private static boolean headerContainsAny(List<String> header, List<String> aliases) {
        for (String alias : aliases) {
            for (String cell : header) {
                if (cell.equals(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The same normalisation the parser uses: case folded, parentheses dropped, trimmed.
     *
     * <p>Kept in step with {@code StatementParser} on purpose. A detector that normalised
     * differently from the parser could confidently name a broker whose profile then failed
     * to find a single column, which is a worse first experience than admitting it does not
     * know.
     */
    private static List<String> splitHeader(String headerLine) {
        List<String> cells = new ArrayList<>();
        for (String raw : stripBom(headerLine).split(",", -1)) {
            String cell = raw.trim();
            if (cell.startsWith("\"") && cell.endsWith("\"") && cell.length() > 1) {
                cell = cell.substring(1, cell.length() - 1);
            }
            int paren = cell.indexOf('(');
            if (paren > 0) {
                cell = cell.substring(0, paren);
            }
            cells.add(cell.trim().toLowerCase(Locale.ROOT));
        }
        return cells;
    }

    private static String stripBom(String line) {
        return line.isEmpty() || line.charAt(0) != '﻿' ? line : line.substring(1);
    }
}
