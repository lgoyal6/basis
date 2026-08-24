package com.basis.cli;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.CommodityClass;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import com.basis.reconcile.BrokerPosition;
import com.basis.reconcile.BrokerPositions;
import com.basis.reconcile.BrokerSnapshot;
import com.basis.reconcile.SnapshotScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Reads a position snapshot in basis's own format.
 *
 * <p>Deliberately not a broker format. Nothing here tries to guess at a particular
 * statement layout; this is a small canonical file that a person or a script produces, so
 * that reconciliation is usable before the transaction statement parsers exist. Real broker
 * layouts are a separate problem and a bigger one.
 *
 * <pre>
 * # comments and blank lines are ignored
 * symbol,quantity,cost_basis,kind
 * AAPL,40,,EQUITY
 * MSFT,10,3000.00,EQUITY
 * USD,1520.55,,CURRENCY
 * </pre>
 *
 * <p>{@code cost_basis} is optional: most statements report a quantity and a market value
 * and nothing else, and an absent cost is not a cost of zero. {@code kind} defaults to
 * EQUITY.
 *
 * <p>Whether the file covers cash is not inferred from whether it happens to contain a
 * currency row. That is stated by the caller, because an absent cash line means "the
 * statement did not say" on most reports and "the balance is zero" on some, and only the
 * person holding the statement knows which.
 */
public final class PositionsFile {

    private PositionsFile() {
    }

    public static BrokerSnapshot read(Path path, Account brokerRoot, LocalDate asOf, SnapshotScope scope) {
        try {
            return parse(Files.readAllLines(path, StandardCharsets.UTF_8), path.toString(),
                    brokerRoot, asOf, scope);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the positions file at " + path, e);
        }
    }

    static BrokerSnapshot parse(
            List<String> lines, String source, Account brokerRoot, LocalDate asOf, SnapshotScope scope) {

        List<BrokerPosition> positions = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#") || isHeader(line)) {
                continue;
            }
            positions.add(parseLine(line, source, index + 1, brokerRoot, scope));
        }
        if (positions.isEmpty()) {
            throw new IllegalArgumentException(source + " contains no positions."
                    + " An empty statement is indistinguishable from a mistake, so it is refused rather"
                    + " than reconciled as if every holding had been closed.");
        }
        return new BrokerSnapshot(brokerRoot, asOf, scope, positions);
    }

    private static boolean isHeader(String line) {
        return line.toLowerCase(Locale.ROOT).startsWith("symbol,");
    }

    private static BrokerPosition parseLine(
            String line, String source, int lineNumber, Account brokerRoot, SnapshotScope scope) {

        String[] fields = line.split(",", -1);
        if (fields.length < 2) {
            throw new IllegalArgumentException(where(source, lineNumber)
                    + " expected symbol,quantity[,cost_basis][,kind] but found: " + line);
        }
        String symbol = fields[0].trim().toUpperCase(Locale.ROOT);
        Quantity quantity = parseQuantity(fields[1], source, lineNumber);
        CommodityClass kind = parseKind(fields.length > 3 ? fields[3] : "", source, lineNumber);

        if (kind == CommodityClass.CURRENCY) {
            if (scope != SnapshotScope.SECURITIES_AND_CASH) {
                throw new IllegalArgumentException(where(source, lineNumber)
                        + " carries a " + symbol + " cash balance, but the snapshot was not declared as"
                        + " covering cash. Pass --with-cash to say the statement includes it.");
            }
            return BrokerPositions.cash(brokerRoot, Money.of(quantity.value(), Currency.getInstance(symbol)));
        }

        Commodity commodity = new Commodity(symbol, kind);
        String rawCost = fields.length > 2 ? fields[2].trim() : "";
        if (rawCost.isEmpty()) {
            return BrokerPositions.held(brokerRoot, commodity, quantity);
        }
        return BrokerPositions.held(brokerRoot, commodity, quantity,
                Money.of(parseDecimal(rawCost, "cost_basis", source, lineNumber), Currency.getInstance("USD")));
    }

    private static Quantity parseQuantity(String raw, String source, int lineNumber) {
        return Quantity.of(parseDecimal(raw.trim(), "quantity", source, lineNumber));
    }

    private static BigDecimal parseDecimal(String raw, String field, String source, int lineNumber) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where(source, lineNumber)
                    + " " + field + " is not a number: '" + raw + "'");
        }
    }

    private static CommodityClass parseKind(String raw, String source, int lineNumber) {
        String kind = raw.trim().toUpperCase(Locale.ROOT);
        if (kind.isEmpty()) {
            return CommodityClass.EQUITY;
        }
        try {
            return CommodityClass.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where(source, lineNumber)
                    + " unknown kind '" + raw.trim() + "'. Expected one of "
                    + java.util.Arrays.toString(CommodityClass.values()));
        }
    }

    private static String where(String source, int lineNumber) {
        return source + ":" + lineNumber;
    }
}
