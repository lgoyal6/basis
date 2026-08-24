package com.basis.reference;

import com.basis.domain.Commodity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which old tickers became which current ones.
 *
 * <p>Resolves transitively, so a security renamed twice still maps to what it is called
 * today. A chain that loops is refused at construction rather than at the first lookup that
 * follows it, because a cycle here is a typo in a hand maintained file and finding it at
 * startup is worth more than finding it during a reconciliation.
 *
 * <p>Empty is a perfectly good state. Most positions were never renamed, and a missing
 * mapping file is not an error.
 */
public final class SymbolMapping {

    private static final int MAX_CHAIN = 20;

    private final Map<String, SymbolChange> byOldSymbol;

    private SymbolMapping(Map<String, SymbolChange> byOldSymbol) {
        this.byOldSymbol = Map.copyOf(byOldSymbol);
    }

    public static SymbolMapping empty() {
        return new SymbolMapping(Map.of());
    }

    /**
     * @throws IllegalArgumentException if a ticker is renamed twice from the same starting
     *     point, or if the chain of renames loops
     */
    public static SymbolMapping of(List<SymbolChange> changes) {
        Map<String, SymbolChange> byOldSymbol = new HashMap<>();
        for (SymbolChange change : changes) {
            SymbolChange previous = byOldSymbol.put(change.from(), change);
            if (previous != null) {
                throw new IllegalArgumentException(change.from() + " is mapped twice, to "
                        + previous.to() + " and to " + change.to()
                        + ". A ticker can only have become one thing.");
            }
        }
        SymbolMapping mapping = new SymbolMapping(byOldSymbol);
        mapping.requireNoCycles();
        return mapping;
    }

    /** What this ticker is called today, following the chain to its end. */
    public String currentSymbol(String symbol) {
        String current = symbol;
        for (int hop = 0; hop <= MAX_CHAIN; hop++) {
            SymbolChange next = byOldSymbol.get(current);
            if (next == null) {
                return current;
            }
            current = next.to();
        }
        throw new IllegalStateException("rename chain from " + symbol + " did not settle within "
                + MAX_CHAIN + " hops");
    }

    /** The same commodity under its current ticker, or the same instance when unchanged. */
    public Commodity resolve(Commodity commodity) {
        String current = currentSymbol(commodity.symbol());
        return current.equals(commodity.symbol())
                ? commodity
                : new Commodity(current, commodity.commodityClass());
    }

    /** True when {@code oldSymbol} is an earlier name for {@code newSymbol}. */
    public boolean renamedTo(String oldSymbol, String newSymbol) {
        return !oldSymbol.equals(newSymbol) && currentSymbol(oldSymbol).equals(currentSymbol(newSymbol));
    }

    /** The individual renames on the path from a ticker to its current name, in order. */
    public List<SymbolChange> chainFrom(String symbol) {
        List<SymbolChange> chain = new ArrayList<>();
        String current = symbol;
        while (chain.size() <= MAX_CHAIN) {
            SymbolChange next = byOldSymbol.get(current);
            if (next == null) {
                return List.copyOf(chain);
            }
            chain.add(next);
            current = next.to();
        }
        throw new IllegalStateException("rename chain from " + symbol + " did not settle");
    }

    /** When the rename that produced today's ticker took effect, if there was one. */
    public Optional<SymbolChange> lastChangeFor(String symbol) {
        List<SymbolChange> chain = chainFrom(symbol);
        return chain.isEmpty() ? Optional.empty() : Optional.of(chain.get(chain.size() - 1));
    }

    public boolean isEmpty() {
        return byOldSymbol.isEmpty();
    }

    public int size() {
        return byOldSymbol.size();
    }

    /** True when the rename had happened by this date, so a statement should use the new name. */
    public boolean appliesBy(String symbol, LocalDate asOf) {
        return lastChangeFor(symbol)
                .map(change -> !change.effective().isAfter(asOf))
                .orElse(false);
    }

    private void requireNoCycles() {
        for (String start : byOldSymbol.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            String current = start;
            while (current != null && seen.add(current)) {
                SymbolChange next = byOldSymbol.get(current);
                current = next == null ? null : next.to();
            }
            if (current != null) {
                throw new IllegalArgumentException("the rename chain starting at " + start
                        + " loops: " + String.join(" to ", seen) + " to " + current);
            }
        }
    }
}
