package com.basis.ledger.lot;

import com.basis.domain.LotSelectionMethod;
import java.util.EnumMap;
import java.util.Map;

/** Looks up the strategy that implements a selection method. */
public final class LotSelectionStrategies {

    private static final Map<LotSelectionMethod, LotSelectionStrategy> BY_METHOD = byMethod(
            new FifoLotSelection(),
            new LifoLotSelection(),
            new HifoLotSelection(),
            new SpecificLotSelection(),
            new AverageCostLotSelection());

    private LotSelectionStrategies() {
    }

    public static LotSelectionStrategy forMethod(LotSelectionMethod method) {
        LotSelectionStrategy strategy = BY_METHOD.get(method);
        if (strategy == null) {
            throw new IllegalStateException("no strategy registered for " + method);
        }
        return strategy;
    }

    private static Map<LotSelectionMethod, LotSelectionStrategy> byMethod(LotSelectionStrategy... strategies) {
        Map<LotSelectionMethod, LotSelectionStrategy> map = new EnumMap<>(LotSelectionMethod.class);
        for (LotSelectionStrategy strategy : strategies) {
            LotSelectionStrategy previous = map.put(strategy.method(), strategy);
            if (previous != null) {
                throw new IllegalStateException("two strategies claim " + strategy.method());
            }
        }
        for (LotSelectionMethod method : LotSelectionMethod.values()) {
            if (!map.containsKey(method)) {
                throw new IllegalStateException(method + " has no strategy. Every method must be answered,"
                        + " even if the answer is a refusal.");
            }
        }
        return Map.copyOf(map);
    }
}
