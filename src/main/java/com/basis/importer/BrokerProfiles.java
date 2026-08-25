package com.basis.importer;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Loads broker profiles from {@code config/brokers}.
 *
 * <p>Java properties files, because a person edits them and the format needs no parser and
 * no dependency. One file per broker, named for the broker, so
 * {@code basis import fidelity} reads {@code config/brokers/fidelity.properties}.
 *
 * <pre>
 * profile.name = Fidelity
 * date.formats = MM/dd/yyyy | M/d/yyyy
 * column.date   = Run Date | Date | Trade Date
 * column.action = Action | Transaction Type
 * action.BUY    = YOU BOUGHT | BOUGHT | PURCHASE
 * </pre>
 *
 * <p>Values are lists separated by {@code |} rather than by commas, because a broker's
 * column really can be called {@code Amount, Net} and a separator that appears inside
 * values is not a separator.
 *
 * <p>A profile that is missing, malformed, or names no header for a column basis needs is
 * an error that says which file and which key. Guessing at a broker's layout and guessing
 * wrong means importing a column of prices as quantities, which is the kind of mistake that
 * reconciles cleanly and is completely wrong.
 */
public final class BrokerProfiles {

    /** Where profiles live, relative to wherever basis is run from. */
    public static final Path DEFAULT_DIRECTORY = Path.of("config", "brokers");

    private static final String SEPARATOR = "\\|";

    private BrokerProfiles() {
    }

    public static BrokerProfile load(String broker) {
        return load(DEFAULT_DIRECTORY, broker);
    }

    public static BrokerProfile load(Path directory, String broker) {
        Path file = directory.resolve(broker.toLowerCase(Locale.ROOT) + ".properties");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("no broker profile at " + file + "."
                    + " Available: " + String.join(", ", available(directory))
                    + ". A new broker is a properties file in " + directory + ", not code.");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return parse(properties, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the broker profile at " + file, e);
        }
    }

    /** Profile names that can be imported, for a usage message that lists real options. */
    public static List<String> available() {
        return available(DEFAULT_DIRECTORY);
    }

    public static List<String> available(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".properties"))
                    .map(name -> name.substring(0, name.length() - ".properties".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list broker profiles in " + directory, e);
        }
    }

    static BrokerProfile parse(Properties properties, String source) {
        String name = properties.getProperty("profile.name", "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException(source + " has no profile.name");
        }

        List<DateTimeFormatter> dateFormats = new ArrayList<>();
        for (String pattern : values(properties, "date.formats")) {
            try {
                dateFormats.add(DateTimeFormatter.ofPattern(pattern, Locale.US));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(source + ": date.formats contains '" + pattern
                        + "', which is not a date pattern");
            }
        }

        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (String column : BrokerProfile.COLUMNS) {
            columns.put(column, normalised(values(properties, "column." + column)));
        }

        Map<ActionKind, List<String>> actions = new EnumMap<>(ActionKind.class);
        for (ActionKind kind : ActionKind.values()) {
            actions.put(kind, upperCased(values(properties, "action." + kind.name())));
        }

        rejectUnknownKeys(properties, source);
        return new BrokerProfile(name, dateFormats, columns, actions);
    }

    /**
     * A key nobody reads is almost always a typo, and a typo in a profile is a column
     * silently unmapped. Cheaper to refuse it than to let an import quietly ignore the
     * Commission column because someone wrote {@code column.commissions}.
     */
    private static void rejectUnknownKeys(Properties properties, String source) {
        List<String> known = new ArrayList<>(List.of("profile.name", "date.formats"));
        BrokerProfile.COLUMNS.forEach(column -> known.add("column." + column));
        for (ActionKind kind : ActionKind.values()) {
            known.add("action." + kind.name());
        }
        List<String> unknown = properties.stringPropertyNames().stream()
                .filter(key -> !known.contains(key))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(source + " has key(s) nothing reads: "
                    + String.join(", ", unknown) + ". Known keys: " + String.join(", ", known));
        }
    }

    private static List<String> values(Properties properties, String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Stream.of(raw.split(SEPARATOR))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** Header matching ignores case and any parenthesised unit, so {@code Price ($)} matches {@code Price}. */
    private static List<String> normalised(List<String> values) {
        return values.stream()
                .map(value -> value.replaceAll("\\(.*?\\)", "").trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static List<String> upperCased(List<String> values) {
        return values.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
    }
}
