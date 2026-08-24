package com.basis.ledger.lot;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.SPY;
import static com.basis.support.Fixtures.VTSAX;
import static com.basis.support.Fixtures.price;
import static com.basis.support.Fixtures.qty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.LotId;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Quantity;
import com.basis.domain.SpecificLotRequest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LotSelectionStrategyTest {

    private static final Account HOLDING = Account.of("Assets:Broker:IBKR:AAPL");

    // Three lots, deliberately not in date order in the list, so a strategy that
    // relies on input order rather than sorting is caught.
    private static final Lot MID = lot("b", "2026-02-01", "120.00", "10");
    private static final Lot OLDEST = lot("c", "2026-01-01", "100.00", "10");
    private static final Lot NEWEST = lot("a", "2026-03-01", "110.00", "10");
    private static final List<Lot> LOTS = List.of(MID, OLDEST, NEWEST);

    @Test
    @DisplayName("FIFO takes the oldest acquisition first")
    void fifoTakesOldestFirst() {
        List<LotConsumption> picked = select(LotSelectionMethod.FIFO, "15");

        assertThat(ids(picked)).containsExactly("c", "b");
        assertThat(picked.get(0).quantity()).isEqualTo(qty("10"));
        assertThat(picked.get(1).quantity()).isEqualTo(qty("5"));
    }

    @Test
    @DisplayName("LIFO takes the newest acquisition first")
    void lifoTakesNewestFirst() {
        List<LotConsumption> picked = select(LotSelectionMethod.LIFO, "15");

        assertThat(ids(picked)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("HIFO takes the highest cost first, which minimises the gain")
    void hifoTakesHighestCostFirst() {
        List<LotConsumption> picked = select(LotSelectionMethod.HIFO, "15");

        assertThat(ids(picked)).containsExactly("b", "a");
    }

    @Test
    @DisplayName("specific lot consumes exactly what was named, in the order it was named")
    void specificLotConsumesWhatWasNamed() {
        List<LotConsumption> picked = LotSelectionStrategies.forMethod(LotSelectionMethod.SPECIFIC_LOT)
                .select(new LotSelectionRequest(HOLDING, AAPL, qty("7"), LOTS, List.of(
                        new SpecificLotRequest(LotId.of("a"), qty("3")),
                        new SpecificLotRequest(LotId.of("c"), qty("4")))));

        assertThat(ids(picked)).containsExactly("a", "c");
        assertThat(picked.get(0).quantity()).isEqualTo(qty("3"));
        assertThat(picked.get(1).quantity()).isEqualTo(qty("4"));
    }

    @Test
    @DisplayName("specific lot naming quantities that do not sum to the disposal is refused")
    void specificLotMustSumToTheDisposal() {
        assertThatThrownBy(() -> LotSelectionStrategies.forMethod(LotSelectionMethod.SPECIFIC_LOT)
                .select(new LotSelectionRequest(HOLDING, AAPL, qty("7"), LOTS,
                        List.of(new SpecificLotRequest(LotId.of("a"), qty("3"))))))
                .isInstanceOf(LotSelectionException.class)
                .hasMessageContaining("7");
    }

    @Test
    @DisplayName("specific lot naming a lot that is not open is refused as unknown, not as insufficient")
    void specificLotRejectsUnknownLot() {
        assertThatThrownBy(() -> LotSelectionStrategies.forMethod(LotSelectionMethod.SPECIFIC_LOT)
                .select(new LotSelectionRequest(HOLDING, AAPL, qty("3"), LOTS,
                        List.of(new SpecificLotRequest(LotId.of("nope"), qty("3"))))))
                .isInstanceOf(UnknownLotException.class)
                .hasMessageContaining("nope");
    }

    @ParameterizedTest
    @EnumSource(value = LotSelectionMethod.class, names = {"FIFO", "LIFO", "HIFO"})
    @DisplayName("every ordered strategy refuses to dispose more than is open")
    void orderedStrategiesRefuseOverdisposal(LotSelectionMethod method) {
        assertThatThrownBy(() -> select(method, "31"))
                .isInstanceOf(InsufficientLotsException.class)
                .hasMessageContaining("30");
    }

    @ParameterizedTest
    @EnumSource(value = LotSelectionMethod.class, names = {"FIFO", "LIFO", "HIFO"})
    @DisplayName("every ordered strategy consumes exactly the requested quantity")
    void orderedStrategiesConsumeExactlyWhatWasAsked(LotSelectionMethod method) {
        Quantity total = Quantity.ZERO;
        for (LotConsumption consumption : select(method, "23.5")) {
            total = total.plus(consumption.quantity());
        }

        assertThat(total).isEqualTo(qty("23.5"));
    }

    @ParameterizedTest
    @EnumSource(value = LotSelectionMethod.class, names = {"FIFO", "LIFO", "HIFO"})
    @DisplayName("lots tied on the primary key are broken by lot id, so selection is a total order")
    void tiesAreBrokenByLotIdSoSelectionIsDeterministic(LotSelectionMethod method) {
        // Same date and same unit cost: only the lot id can order these.
        List<Lot> tied = List.of(
                lot("z", "2026-01-01", "100.00", "10"),
                lot("m", "2026-01-01", "100.00", "10"),
                lot("d", "2026-01-01", "100.00", "10"));

        List<LotConsumption> picked = LotSelectionStrategies.forMethod(method)
                .select(new LotSelectionRequest(HOLDING, AAPL, qty("15"), tied, List.of()));

        assertThat(ids(picked)).containsExactly("d", "m");
    }

    @Test
    @DisplayName("HIFO refuses to rank lots across currencies without a rate")
    void hifoRefusesMixedCurrencies() {
        List<Lot> mixed = List.of(
                lot("a", "2026-01-01", "100.00", "10"),
                new Lot(LotId.of("b"), HOLDING, AAPL, LocalDate.of(2026, 1, 2),
                        com.basis.domain.Price.of("90.00", com.basis.support.Fixtures.EUR),
                        qty("10"), qty("10")));

        assertThatThrownBy(() -> LotSelectionStrategies.forMethod(LotSelectionMethod.HIFO)
                .select(new LotSelectionRequest(HOLDING, AAPL, qty("5"), mixed, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exchange rate");
    }

    @Test
    @DisplayName("average cost on an equity is not permitted, and never will be")
    void averageCostIsNotPermittedForEquities() {
        assertThatThrownBy(() -> averageCost(AAPL))
                .isInstanceOf(AverageCostNotPermittedException.class)
                .hasMessageContaining("EQUITY");
    }

    @Test
    @DisplayName("average cost on an ETF is not permitted either")
    void averageCostIsNotPermittedForEtfs() {
        assertThatThrownBy(() -> averageCost(SPY))
                .isInstanceOf(AverageCostNotPermittedException.class);
    }

    @Test
    @DisplayName("average cost on a mutual fund is permitted but unimplemented, a different refusal")
    void averageCostOnAMutualFundIsUnimplementedNotForbidden() {
        assertThatThrownBy(() -> averageCost(VTSAX))
                .isInstanceOf(UnsupportedOperationException.class)
                .isNotInstanceOf(LotSelectionException.class)
                .hasMessageContaining("not implemented");
    }

    @Test
    @DisplayName("every selection method has a strategy, even if the answer is a refusal")
    void everyMethodIsAnswered() {
        for (LotSelectionMethod method : LotSelectionMethod.values()) {
            assertThat(LotSelectionStrategies.forMethod(method).method()).isEqualTo(method);
        }
    }

    private static List<LotConsumption> averageCost(Commodity commodity) {
        return LotSelectionStrategies.forMethod(LotSelectionMethod.AVERAGE_COST)
                .select(new LotSelectionRequest(HOLDING, commodity, qty("5"),
                        List.of(lot("a", "2026-01-01", "100.00", "10")), List.of()));
    }

    private static List<LotConsumption> select(LotSelectionMethod method, String quantity) {
        return LotSelectionStrategies.forMethod(method)
                .select(new LotSelectionRequest(HOLDING, AAPL, qty(quantity), LOTS, List.of()));
    }

    private static List<String> ids(List<LotConsumption> consumptions) {
        return consumptions.stream().map(consumption -> consumption.lot().id().value()).toList();
    }

    private static Lot lot(String id, String date, String unitCost, String quantity) {
        return Lot.opened(LotId.of(id), HOLDING, AAPL, LocalDate.parse(date), price(unitCost), qty(quantity));
    }
}
