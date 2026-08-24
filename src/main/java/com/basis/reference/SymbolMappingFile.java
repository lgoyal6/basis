package com.basis.reference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the hand maintained ticker rename file.
 *
 * <p>CSV rather than anything cleverer, because a human edits it. Blank lines and lines
 * starting with {@code #} are ignored, so the file can explain itself to the next person
 * who has to add a row to it.
 *
 * <p>A missing file is not an error. Most histories contain no renamed securities, and
 * making the absence of a file fatal would mean every user creating an empty one.
 *
 * <p>A malformed line is an error, and names its line number. The alternative is skipping
 * it, which would mean a typo silently costs someone the rename that explains their break.
 */
public final class SymbolMappingFile {

    private static final Logger log = LoggerFactory.getLogger(SymbolMappingFile.class);

    private SymbolMappingFile() {
    }

    /** Loads the mapping, returning an empty one when the file does not exist. */
    public static SymbolMapping load(Path path) {
        if (!Files.exists(path)) {
            log.debug("no ticker rename file at {}, continuing with no renames", path);
            return SymbolMapping.empty();
        }
        try {
            SymbolMapping mapping = parse(Files.readAllLines(path, StandardCharsets.UTF_8), path.toString());
            log.debug("loaded {} ticker renames from {}", mapping.size(), path);
            return mapping;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the ticker rename file at " + path, e);
        }
    }

    /** Visible for tests, and for validating a file before it is committed. */
    public static SymbolMapping parse(List<String> lines, String source) {
        List<SymbolChange> changes = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (isHeader(line)) {
                continue;
            }
            changes.add(parseLine(line, source, index + 1));
        }
        return SymbolMapping.of(changes);
    }

    private static boolean isHeader(String line) {
        return line.toLowerCase(java.util.Locale.ROOT).startsWith("old_symbol,");
    }

    private static SymbolChange parseLine(String line, String source, int lineNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length < 3) {
            throw new IllegalArgumentException(where(source, lineNumber)
                    + " expected old_symbol,new_symbol,effective_date[,note] but found " + fields.length
                    + " field(s): " + line);
        }
        LocalDate effective;
        try {
            effective = LocalDate.parse(fields[2].trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where(source, lineNumber)
                    + " effective date must be yyyy-mm-dd but was '" + fields[2].trim() + "'");
        }
        try {
            return new SymbolChange(fields[0], fields[1], effective, fields.length > 3 ? fields[3] : "");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where(source, lineNumber) + " " + e.getMessage());
        }
    }

    private static String where(String source, int lineNumber) {
        return source + ":" + lineNumber;
    }
}
