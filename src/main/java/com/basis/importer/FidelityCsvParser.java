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
 * Reads Fidelity's Accounts History CSV export.
 *
 * <p>Built from knowledge of the format rather than from a real export, which is worth
 * stating plainly at the top of the file that depends on it being right. Everything that is
 * likely to be wrong is therefore data rather than code: the column names live in
 * {@link #COLUMN_ALIASES} and the action words live in {@link FidelityActions}, so
 * correcting this against a real file is an edit to a table, not a rewrite.
 *
 * <p>Three things about the file shape drive the design.
 *
 * <ul>
 *   <li>The CSV body is <b>surrounded by junk</b>. Fidelity puts a title line and blank
 *       lines above the header, and a block of disclaimer prose below the last row. So the
 *       header is found by looking for it, and the body ends at the first line that is not
 *       a data row.
 *   <li>Columns are matched <b>by name, not position</b>. The column order has changed
 *       between exports, and a positional reader silently puts a price where a quantity
 *       belongs rather than failing.
 *   <li>Descriptions contain commas, so fields are split with quotes respected. See
 *       {@link CsvLine}.
 * </ul>
 */
public final class FidelityCsvParser {

    /**
     * Column names this parser understands, each mapped to the aliases seen in the wild.
     *
     * <p>Matching is case insensitive and ignores anything in parentheses, so
     * {@code Price ($)} and {@code Price} are the same column. Add an alias here when a real
     * export disagrees.
     */
    private static final Map<String, List<String>> COLUMN_ALIASES = new LinkedHashMap<>();

    static {
        COLUMN_ALIASES.put("date", List.of("run date", "date", "trade date", "settlement date"));
        COLUMN_ALIASES.put("action", List.of("action", "transaction type", "description of transaction"));
        COLUMN_ALIASES.put("symbol", List.of("symbol", "ticker"));
        COLUMN_ALIASES.put("description", List.of("description", "security description"));
        COLUMN_ALIASES.put("quantity", List.of("quantity", "shares"));
        COLUMN_ALIASES.put("price", List.of("price", "price per share", "average price"));
        COLUMN_ALIASES.put("commission", List.of("commission"));
        COLUMN_ALIASES.put("fees", List.of("fees", "fee"));
        COLUMN_ALIASES.put("amount", List.of("amount", "net amount", "net cash amount"));
    }

    /** Fidelity writes US dates. Both are accepted because exports differ by locale setting. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private FidelityCsvParser() {
    }

    public static List<StatementRow> read(Path path) {
        try {
            return parse(Files.readAllLines(path, StandardCharsets.UTF_8), path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the statement at " + path, e);
        }
    }

    public static List<StatementRow> parse(List<String> lines, String source) {
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
     * the first non blank line instead would pick up Fidelity's title row.
     */
    private static int findHeader(List<String> lines, String source) {
        for (int index = 0; index < lines.size(); index++) {
            List<String> fields = CsvLine.split(lines.get(index));
            if (indexOf(fields, "date") >= 0 && indexOf(fields, "action") >= 0) {
                return index;
            }
        }
        throw new IllegalArgumentException(source + " does not look like a Fidelity export:"
                + " no header row naming a date column and an action column was found."
                + " Expected something like 'Run Date,Action,Symbol,Description,Quantity,Price,Amount'.");
    }

    private static Map<String, Integer> mapColumns(List<String> header, String source, int lineNumber) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (String column : COLUMN_ALIASES.keySet()) {
            int position = indexOf(header, column);
            if (position >= 0) {
                columns.put(column, position);
            }
        }
        for (String required : List.of("date", "action", "amount")) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException(source + ":" + lineNumber + " the header has no "
                        + required + " column. Found: " + header
                        + ". Add the real name to FidelityCsvParser.COLUMN_ALIASES.");
            }
        }
        return Map.copyOf(columns);
    }

    /** Matches a header cell to a known column, ignoring case and any parenthesised unit. */
    private static int indexOf(List<String> header, String column) {
        List<String> aliases = COLUMN_ALIASES.get(column);
        for (int index = 0; index < header.size(); index++) {
            String cell = normalise(header.get(index));
            if (aliases.contains(cell)) {
                return index;
            }
        }
        return -1;
    }

    private static String normalise(String cell) {
        return cell.replaceAll("\\(.*?\\)", "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * True once the transactions have run out.
     *
     * <p>Fidelity appends disclaimer prose after the last row. Those lines have no date in
     * the date column, which is what distinguishes them from data without having to guess at
     * their wording.
     */
    private static boolean isEndOfBody(String line, Map<String, Integer> columns) {
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

    private static StatementRow parseRow(
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

    private static LocalDate parseDateOrNull(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        // Some exports append a settlement date in the same cell, as "01/15/2026 as of 01/13/2026".
        int asOf = value.toLowerCase(Locale.ROOT).indexOf(" as of ");
        if (asOf > 0) {
            value = value.substring(0, asOf).trim();
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next shape.
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
