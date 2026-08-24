package com.basis.support;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.SpecificLotRequest;
import com.basis.domain.Transaction;
import com.basis.domain.event.Buy;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.Sell;
import com.basis.ledger.Ledger;
import com.basis.ledger.LedgerAccounts;
import com.basis.ledger.LedgerState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a generated list of {@link Intent}s against a ledger, tracking independently what
 * the answer ought to be.
 *
 * <p>Intents are not events. A generated sell says "dispose 40 percent of whatever is
 * held" rather than naming a quantity, because a generator that named quantities would
 * spend nearly every try producing a disposal larger than the position and testing the
 * error path instead of the arithmetic. Turning intents into events here, against the
 * live position, is what keeps the generated histories legal and interesting.
 *
 * <p>Expected cash is accumulated from the intents as they are interpreted, using
 * nothing the ledger produced. That is what makes invariant 6 an independent check
 * rather than a restatement of invariant 1.
 */
public final class GeneratedHistory {

    /** What kind of thing a generated step is. */
    public enum Kind {
        BUY,
        SELL,
        FEE
    }

    /**
     * One generated step, before it knows what the ledger holds.
     *
     * @param sellPercent how much of the current holding a sell disposes of, 1 to 100
     */
    public record Intent(
            Kind kind,
            int commodityIndex,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal commission,
            int sellPercent,
            LotSelectionMethod method) {
    }

    /** The commodities a generated history trades in. */
    public static final List<Commodity> COMMODITIES = List.of(Fixtures.AAPL, Fixtures.MSFT, Fixtures.SPY);

    private static final LocalDate START = LocalDate.of(2026, 1, 5);
    private static final String OPENING_CASH = "1000000.00";

    private final Ledger ledger = new Ledger();
    private final List<Transaction> recorded = new ArrayList<>();
    private final List<ExpectedSale> expectedSales = new ArrayList<>();
    private Money expectedCash = Money.zero(Fixtures.USD);
    private int applied;
    private int skipped;

    /** What a sale should have realized, computed from the event and the lots, not the ledger. */
    public record ExpectedSale(Money proceeds, Quantity quantity, Commodity commodity) {
    }

    /** Runs every intent it can, skipping the ones the position cannot support. */
    public static GeneratedHistory run(List<Intent> intents) {
        GeneratedHistory history = new GeneratedHistory();
        history.openCash();
        for (int index = 0; index < intents.size(); index++) {
            history.apply(intents.get(index), index);
        }
        return history;
    }

    /**
     * Runs every intent, handing the history to {@code afterEachStep} once per applied
     * step. Used by the property that asserts the invariants after every step rather than
     * only at the end, which is where a bug that cancels itself out over a long history
     * gets caught.
     */
    public static GeneratedHistory runChecking(List<Intent> intents, Consumer<GeneratedHistory> afterEachStep) {
        GeneratedHistory history = new GeneratedHistory();
        history.openCash();
        afterEachStep.accept(history);
        for (int index = 0; index < intents.size(); index++) {
            if (history.apply(intents.get(index), index)) {
                afterEachStep.accept(history);
            }
        }
        return history;
    }

    private void openCash() {
        record(Fixtures.openingCash(START, "open", OPENING_CASH));
        expectedCash = expectedCash.plus(Fixtures.usd(OPENING_CASH));
    }

    /** @return true if the intent produced a transaction */
    private boolean apply(Intent intent, int index) {
        // Two steps share each date, so lots collide on acquisition date and the lot id
        // tiebreak in every ordered strategy actually gets exercised.
        LocalDate date = START.plusDays(1L + index / 2);
        Commodity commodity = COMMODITIES.get(Math.floorMod(intent.commodityIndex(), COMMODITIES.size()));
        String ref = "step-" + index;

        return switch (intent.kind()) {
            case BUY -> applyBuy(intent, date, commodity, ref);
            case SELL -> applySell(intent, date, commodity, ref);
            case FEE -> applyFee(intent, date, ref);
        };
    }

    private boolean applyBuy(Intent intent, LocalDate date, Commodity commodity, String ref) {
        Quantity quantity = Quantity.of(intent.quantity());
        if (!quantity.isPositive()) {
            skipped++;
            return false;
        }
        Price price = Price.of(intent.unitPrice(), Fixtures.USD);
        Money commission = Money.of(intent.commission(), Fixtures.USD);

        record(new Buy(date, Fixtures.IBKR, ref, Fixtures.sourceRow(ref), commodity,
                quantity, price, commission));

        Money gross = Money.round(quantity.multiplyBy(price), Fixtures.USD);
        expectedCash = expectedCash.minus(gross).minus(commission);
        return true;
    }

    private boolean applySell(Intent intent, LocalDate date, Commodity commodity, String ref) {
        Account holding = LedgerAccounts.holding(Fixtures.IBKR, commodity);
        List<Lot> open = ledger.state().openLots(holding, commodity);
        if (open.isEmpty()) {
            skipped++;
            return false;
        }
        Price price = Price.of(intent.unitPrice(), Fixtures.USD);
        Money commission = Money.of(intent.commission(), Fixtures.USD);

        Sell sell = intent.method() == LotSelectionMethod.SPECIFIC_LOT
                ? specificLotSell(intent, date, commodity, ref, open, price, commission)
                : proportionalSell(intent, date, commodity, ref, holding, price, commission);
        if (sell == null) {
            skipped++;
            return false;
        }

        record(sell);
        Money gross = sell.grossProceeds();
        expectedCash = expectedCash.plus(gross).minus(commission);
        expectedSales.add(new ExpectedSale(gross, sell.quantity(), commodity));
        return true;
    }

    private Sell proportionalSell(Intent intent, LocalDate date, Commodity commodity, String ref,
            Account holding, Price price, Money commission) {
        Quantity held = ledger.state().position(holding, commodity);
        BigDecimal fraction = held.value()
                .multiply(BigDecimal.valueOf(intent.sellPercent()))
                .divide(BigDecimal.valueOf(100), Quantity.SCALE, RoundingMode.HALF_EVEN);
        Quantity quantity = Quantity.of(fraction);
        if (!quantity.isPositive()) {
            return null;
        }
        return new Sell(date, Fixtures.IBKR, ref, Fixtures.sourceRow(ref), commodity,
                quantity, price, commission, intent.method(), List.of());
    }

    /** Names a prefix of the open lots and consumes each in full, so the request is always legal. */
    private Sell specificLotSell(Intent intent, LocalDate date, Commodity commodity, String ref,
            List<Lot> open, Price price, Money commission) {
        int count = 1 + Math.floorMod(intent.sellPercent(), open.size());
        List<SpecificLotRequest> named = new ArrayList<>();
        Quantity total = Quantity.ZERO;
        for (int i = 0; i < count; i++) {
            Lot lot = open.get(i);
            named.add(new SpecificLotRequest(lot.id(), lot.remainingQuantity()));
            total = total.plus(lot.remainingQuantity());
        }
        if (!total.isPositive()) {
            return null;
        }
        return new Sell(date, Fixtures.IBKR, ref, Fixtures.sourceRow(ref), commodity,
                total, price, commission, LotSelectionMethod.SPECIFIC_LOT, named);
    }

    private boolean applyFee(Intent intent, LocalDate date, String ref) {
        Money amount = Money.of(intent.commission(), Fixtures.USD);
        if (!amount.isPositive()) {
            skipped++;
            return false;
        }
        record(Fixtures.fee(date, ref, amount.toMajorUnits().toPlainString()));
        expectedCash = expectedCash.minus(amount);
        return true;
    }

    private void record(LedgerEvent event) {
        recorded.add(ledger.record(event));
        applied++;
    }

    public LedgerState state() {
        return ledger.state();
    }

    public List<Transaction> recorded() {
        return List.copyOf(recorded);
    }

    public Money expectedCash() {
        return expectedCash;
    }

    public List<ExpectedSale> expectedSales() {
        return List.copyOf(expectedSales);
    }

    public Account cashAccount() {
        return Fixtures.IBKR_CASH;
    }

    public int applied() {
        return applied;
    }

    public int skipped() {
        return skipped;
    }
}
