package com.basis.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a broker's CSV export into neutral rows, following a {@link BrokerProfile}.
 *
 * <p>Nothing here knows which broker it is reading. The column names, the action wording
 * and the date formats come from the profile; what stays in code is the handling that every
 * spreadsheet export shares.
 *
 * <p>Three of those behaviours are worth naming, because they are why this is not four lines
 * of {@code split(",")}.
 *
 * <ul>
 *   <li>The CSV body is <b>surrounded by junk</b>. Exports put a title and blank lines above
 *       the header and disclaimer prose below the last row. So the header is found by
 *       looking for a line that names both a date and an action column, and the body ends at
 *       the first line with no date in it.
 *   <li>Columns are matched <b>by name, not position</b>. Column order changes between
 *       exports, and a positional reader silently puts a price where a quantity belongs
 *       rather than failing.
 *   <li>Descriptions contain commas, so fields are split with quotes respected.
 *       {@code "APPLE INC, COM"} would otherwise shift every column after it.
 * </ul>
 */
public final class StatementParser {

    private final BrokerProfile profile;

    public StatementParser(BrokerProfile profile) {
        this.profile = profile;
    }

    public List<StatementRow> read(Path path) {
        try {
            return parse(Files.readAllLines(path, StandardCharsets.UTF_8), path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the statement at " + path, e);
        }
    }

    public List<StatementRow> parse(List<String> rawLines, String source) {
        // The real export starts with a byte order mark. It lands on the first line here, but
        // a file whose header is the first line would otherwise have an invisible character
        // glued to its first column name and match nothing.
        List<String> lines = new ArrayList<>(rawLines);
        if (!lines.isEmpty()) {
            lines.set(0, lines.get(0).replace("\uFEFF", ""));
        }
        int headerIndex = findHeader(lines, source);
        Map<String, Integer> columns = mapColumns(CsvLine.split(lines.get(headerIndex)), source, headerIndex + 1);

        List<StatementRow> rows = new ArrayList<>();
        for (int index = headerIndex + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (isEndOfBody(line, columns)) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            rows.add(parseRow(line, columns, source, index + 1, rows.size() + 1));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(source + " has a header but no transactions."
                    + " An empty statement is indistinguishable from a truncated download, so it is"
                    + " refused rather than imported as if nothing had happened.");
        }
        return List.copyOf(rows);
    }

    /**
     * Finds the header line, skipping whatever preamble the export put above it.
     *
     * <p>A line is the header when it names both a date column and an action column. Taking
     * the first non blank line instead would pick up the export's title row.
     */
    private int findHeader(List<String> lines, String source) {
        for (int index = 0; index < lines.size(); index++) {
            List<String> fields = CsvLine.split(lines.get(index));
            if (indexOf(fields, "date") >= 0 && indexOf(fields, "action") >= 0) {
                return index;
            }
        }
        throw new IllegalArgumentException(source + " does not look like a " + profile.name()
                + " export: no header row naming a date column and an action column was found."
                + " Expected a date column called one of " + profile.aliasesFor("date")
                + " and an action column called one of " + profile.aliasesFor("action") + ".");
    }

    private Map<String, Integer> mapColumns(List<String> header, String source, int lineNumber) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (String column : BrokerProfile.COLUMNS) {
            int position = indexOf(header, column);
            if (position >= 0) {
                columns.put(column, position);
            }
        }
        for (String required : BrokerProfile.REQUIRED_COLUMNS) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException(source + ":" + lineNumber + " the header has no "
                        + required + " column. Found: " + header
                        + ". Add the real name to column." + required + " in the " + profile.name()
                        + " profile.");
            }
        }
        return Map.copyOf(columns);
    }

    /**
     * Matches a header cell to a column basis needs, ignoring case and any parenthesised unit.
     *
     * <p>Aliases are tried in the order the profile lists them, not in the order the header
     * happens to be written. A real Fidelity export contains both "Run Date" and "Settlement
     * Date", and both are dates a profile might name. Scanning the header instead would pick
     * whichever came first on the page, which is luck rather than a rule, and the two are
     * different days.
     */
    private int indexOf(List<String> header, String column) {
        for (String alias : profile.aliasesFor(column)) {
            for (int index = 0; index < header.size(); index++) {
                if (normalise(header.get(index)).equals(alias)) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** Strips a byte order mark, lower cases, and drops any parenthesised unit. */
    private static String normalise(String cell) {
        return cell.replace("\uFEFF", "")
                .replaceAll("\\(.*?\\)", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * True once the transactions have run out.
     *
     * <p>Exports append disclaimer prose after the last row. Those lines have no date in the
     * date column, which distinguishes them from data without guessing at their wording.
     */
    private boolean isEndOfBody(String line, Map<String, Integer> columns) {
        if (line.isBlank()) {
            return false;
        }
        List<String> fields = CsvLine.split(line);
        int dateColumn = columns.get("date");
        if (fields.size() <= dateColumn) {
            return true;
        }
        return parseDateOrNull(fields.get(dateColumn)) == null;
    }

    private StatementRow parseRow(
            String line, Map<String, Integer> columns, String source, int lineNumber, int ordinal) {

        List<String> fields = CsvLine.split(line);
        LocalDate date = parseDateOrNull(field(fields, columns, "date"));
        if (date == null) {
            throw new IllegalArgumentException(source + ":" + lineNumber
                    + " could not read a date from '" + field(fields, columns, "date") + "'");
        }
        return new StatementRow(
                ordinal,
                date,
                field(fields, columns, "action"),
                field(fields, columns, "symbol"),
                field(fields, columns, "description"),
                parseAmount(field(fields, columns, "quantity"), source, lineNumber, "quantity"),
                parseAmount(field(fields, columns, "price"), source, lineNumber, "price"),
                parseAmount(field(fields, columns, "commission"), source, lineNumber, "commission"),
                parseAmount(field(fields, columns, "fees"), source, lineNumber, "fees"),
                parseAmount(field(fields, columns, "amount"), source, lineNumber, "amount"),
                line);
    }

    private static String field(List<String> fields, Map<String, Integer> columns, String column) {
        Integer position = columns.get(column);
        if (position == null || position >= fields.size()) {
            return "";
        }
        return fields.get(position).trim();
    }

    private LocalDate parseDateOrNull(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        // Some exports append a settlement date in the same cell, as "01/15/2026 as of 01/13/2026".
        int asOf = value.toLowerCase(Locale.ROOT).indexOf(" as of ");
        if (asOf > 0) {
            value = value.substring(0, asOf).trim();
        }
        for (DateTimeFormatter format : profile.dateFormats()) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next shape the profile names.
            }
        }
        return null;
    }

    /**
     * Reads a money or quantity cell, tolerating the decorations a spreadsheet export adds.
     *
     * @return null when the cell is empty, which is different from zero: an absent price on
     *     a dividend row is not a price of nothing
     */
    private static BigDecimal parseAmount(String raw, String source, int lineNumber, String field) {
        String value = raw.replace("$", "").replace(",", "").replace(" ", "").trim();
        if (value.isEmpty() || value.equals("-")) {
            return null;
        }
        boolean parenthesised = value.startsWith("(") && value.endsWith(")");
        if (parenthesised) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            return parenthesised ? parsed.negate() : parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(source + ":" + lineNumber
                    + " " + field + " is not a number: '" + raw + "'");
        }
    }
}
