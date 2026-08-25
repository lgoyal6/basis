package com.basis.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one CSV line into fields, respecting quotes.
 *
 * <p>Written rather than pulled in, because the whole requirement is quoted fields and
 * doubled quotes, and a CSV library is a large dependency for forty lines of state machine.
 *
 * <p>Naive splitting on commas is not an option here. Fidelity's Description column carries
 * things like {@code "APPLE INC, COM"}, so a positional split would silently shift every
 * column after it and put a price where a quantity belongs.
 */
final class CsvLine {

    private CsvLine() {
    }

    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (inQuotes) {
                if (character != '"') {
                    field.append(character);
                } else if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    // A doubled quote inside a quoted field is one literal quote.
                    field.append('"');
                    index++;
                } else {
                    inQuotes = false;
                }
                continue;
            }
            switch (character) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    fields.add(field.toString().trim());
                    field.setLength(0);
                }
                default -> field.append(character);
            }
        }
        fields.add(field.toString().trim());
        return List.copyOf(fields);
    }
}
