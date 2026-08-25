package com.basis.reference;

import com.basis.domain.Commodity;
import com.basis.domain.CommodityClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What kind of instrument each ticker is.
 *
 * <p>Statements do not say. A real Fidelity export describes FXAIX only as "FIDELITY 500
 * INDEX FUND", and no amount of reading that string is a safe way to decide whether US rules
 * let you average its cost basis. So the answer is declared rather than guessed, in
 * {@code config/commodities.csv}, and anything undeclared is an ordinary equity.
 *
 * <p>Defaulting to equity is the conservative direction: the only thing it costs is that
 * average cost is refused for a fund nobody has declared, which is a message telling you to
 * add a line. Defaulting the other way would let an equity be averaged, which is not
 * permitted and which no error would ever mention.
 *
 * <p>This matters more than it looks. A commodity's class is part of its identity, so a fund
 * imported as an equity and an election made against it as a fund refer to two different
 * things and the election finds nothing to average.
 */
public final class CommodityCatalog {

    private static final Logger log = LoggerFactory.getLogger(CommodityCatalog.class);

    /** Where the declarations live, relative to wherever basis is run from. */
    public static final Path DEFAULT_FILE = Path.of("config", "commodities.csv");

    private final Map<String, CommodityClass> bySymbol;

    private CommodityCatalog(Map<String, CommodityClass> bySymbol) {
        this.bySymbol = Map.copyOf(bySymbol);
    }

    public static CommodityCatalog empty() {
        return new CommodityCatalog(Map.of());
    }

    public static CommodityCatalog load() {
        return load(DEFAULT_FILE);
    }

    /** A missing file means everything is an equity, which is a fine place to start. */
    public static CommodityCatalog load(Path path) {
        if (!Files.exists(path)) {
            log.debug("no commodity catalog at {}, treating every symbol as an equity", path);
            return empty();
        }
        try {
            return parse(Files.readAllLines(path, StandardCharsets.UTF_8), path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the commodity catalog at " + path, e);
        }
    }

    /** Visible so a declaration can be validated without writing a file. */
    public static CommodityCatalog parse(List<String> lines, String source) {
        Map<String, CommodityClass> bySymbol = new HashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")
                    || line.toLowerCase(Locale.ROOT).startsWith("symbol,")) {
                continue;
            }
            String[] fields = line.split(",", -1);
            if (fields.length < 2) {
                throw new IllegalArgumentException(source + ":" + (index + 1)
                        + " expected symbol,kind but found: " + line);
            }
            String symbol = fields[0].trim().toUpperCase(Locale.ROOT);
            try {
                bySymbol.put(symbol, CommodityClass.valueOf(fields[1].trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(source + ":" + (index + 1) + " unknown kind '"
                        + fields[1].trim() + "'. Expected one of "
                        + java.util.Arrays.toString(CommodityClass.values()));
            }
        }
        return new CommodityCatalog(bySymbol);
    }

    /** The commodity for a ticker, as an equity unless something says otherwise. */
    public Commodity resolve(String symbol) {
        String ticker = symbol.trim().toUpperCase(Locale.ROOT);
        CommodityClass declared = bySymbol.getOrDefault(ticker, CommodityClass.EQUITY);
        return declared == CommodityClass.CURRENCY
                ? Commodity.of(java.util.Currency.getInstance(ticker))
                : new Commodity(ticker, declared);
    }

    public int size() {
        return bySymbol.size();
    }
}
