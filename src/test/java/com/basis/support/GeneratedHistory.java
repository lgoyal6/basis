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
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.Fee;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.OpeningBalance;
import com.basis.domain.event.ReverseSplit;
import com.basis.domain.event.Sell;
import com.basis.domain.event.Split;
import com.basis.domain.event.Transfer;
import com.basis.ledger.Ledger;
import com.basis.ledger.LedgerAccounts;
import com.basis.ledger.LedgerState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Expected cash is accumulated from the intents as they are interpreted, per account,
 * using nothing the ledger produced. That is what makes invariant 6 an independent check
 * rather than a restatement of invariant 1.
 *
 * <p>Two brokers, so that transfers have somewhere to go, positions have to stay
 * separable by account, and cash conservation has to hold per account rather than only in
 * total. A bug that moves cash to the wrong account conserves the total perfectly.
 */
public final class GeneratedHistory {

    /** What kind of thing a generated step is. */
    public enum Kind {
        BUY,
        SELL,
        FEE,
        DIVIDEND,
        TRANSFER_CASH,
        TRANSFER_SECURITY,
        SPLIT
    }

    /**
     * One generated step, before it knows what the ledger holds.
     *
     * @param accountIndex which broker the step happens in. For a transfer this is the
     *     sending side and the other broker receives.
     * @param percent how much of the current holding, cash balance or dividend a step
     *     takes, 1 to 100
     */
    public record Intent(
            Kind kind,
            int commodityIndex,
            int accountIndex,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            int percent,
            LotSelectionMethod method) {
    }

    /** The commodities a generated history trades in. */
    public static final List<Commodity> COMMODITIES = List.of(Fixtures.AAPL, Fixtures.MSFT, Fixtures.SPY);

    /** The brokers a generated history trades in. */
    public static final List<Account> BROKERS = List.of(Fixtures.IBKR, Fixtures.SCHWAB);

    private static final LocalDate START = LocalDate.of(2026, 1, 5);
    private static final String OPENING_CASH = "1000000.00";

    private final Ledger ledger = new Ledger();
    private final List<Transaction> recorded = new ArrayList<>();
    private final List<ExpectedSale> expectedSales = new ArrayList<>();
    private final Map<Account, Money> expectedCash = new LinkedHashMap<>();
    private int applied;
    private int skipped;

    /** What a sale should have realized, computed from the event and not from the ledger. */
    public record ExpectedSale(Money proceeds, Quantity quantity, Commodity commodity) {
    }

    /** Runs every intent it can, skipping the ones the position or balance cannot support. */
    public static GeneratedHistory run(List<Intent> intents) {
        return runChecking(intents, history -> {
        });
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
        for (Account broker : BROKERS) {
            record(new OpeningBalance(START, broker, "open-" + broker.leaf(),
                    Fixtures.sourceRow("open-" + broker.leaf()),
                    Commodity.of(Fixtures.USD), Quantity.of(OPENING_CASH), null));
            addCash(broker, Fixtures.usd(OPENING_CASH));
        }
    }

    /** @return true if the intent produced a transaction */
    private boolean apply(Intent intent, int index) {
        // Two steps share each date, so lots collide on acquisition date and the lot id
        // tiebreak in every ordered strategy actually gets exercised.
        LocalDate date = START.plusDays(1L + index / 2);
        Commodity commodity = COMMODITIES.get(Math.floorMod(intent.commodityIndex(), COMMODITIES.size()));
        Account broker = BROKERS.get(Math.floorMod(intent.accountIndex(), BROKERS.size()));
        Account other = BROKERS.get(1 - BROKERS.indexOf(broker));
        String ref = "step-" + index;

        return switch (intent.kind()) {
            case BUY -> applyBuy(intent, date, broker, commodity, ref);
            case SELL -> applySell(intent, date, broker, commodity, ref);
            case FEE -> applyFee(intent, date, broker, ref);
            case DIVIDEND -> applyDividend(intent, date, broker, commodity, ref);
            case TRANSFER_CASH -> applyCashTransfer(intent, date, broker, other, ref);
            case TRANSFER_SECURITY -> applySecurityTransfer(intent, date, broker, other, commodity, ref);
            case SPLIT -> applySplit(intent, date, broker, commodity, ref);
        };
    }

    private boolean applyBuy(Intent intent, LocalDate date, Account broker, Commodity commodity, String ref) {
        Quantity quantity = Quantity.of(intent.quantity());
        if (!quantity.isPositive()) {
            return skip();
        }
        Price price = Price.of(intent.unitPrice(), Fixtures.USD);
        Money commission = Money.of(intent.amount(), Fixtures.USD);

        record(new Buy(date, broker, ref, Fixtures.sourceRow(ref), commodity, quantity, price, commission));

        addCash(broker, Money.round(quantity.multiplyBy(price), Fixtures.USD).negate());
        addCash(broker, commission.negate());
        return true;
    }

    private boolean applySell(Intent intent, LocalDate date, Account broker, Commodity commodity, String ref) {
        Account holding = LedgerAccounts.holding(broker, commodity);
        List<Lot> open = ledger.state().openLots(holding, commodity);
        if (open.isEmpty()) {
            return skip();
        }
        Price price = Price.of(intent.unitPrice(), Fixtures.USD);
        Money commission = Money.of(intent.amount(), Fixtures.USD);

        Sell sell = intent.method() == LotSelectionMethod.SPECIFIC_LOT
                ? specificLotSell(intent, date, broker, commodity, ref, open, price, commission)
                : proportionalSell(intent, date, broker, commodity, ref, holding, price, commission);
        if (sell == null) {
            return skip();
        }

        record(sell);
        addCash(broker, sell.grossProceeds());
        addCash(broker, commission.negate());
        expectedSales.add(new ExpectedSale(sell.grossProceeds(), sell.quantity(), commodity));
        return true;
    }

    private Sell proportionalSell(Intent intent, LocalDate date, Account broker, Commodity commodity, String ref,
            Account holding, Price price, Money commission) {
        Quantity quantity = fractionOf(ledger.state().position(holding, commodity), intent.percent());
        if (!quantity.isPositive()) {
            return null;
        }
        return new Sell(date, broker, ref, Fixtures.sourceRow(ref), commodity,
                quantity, price, commission, intent.method(), List.of());
    }

    /** Names a prefix of the open lots and consumes each in full, so the request is always legal. */
    private Sell specificLotSell(Intent intent, LocalDate date, Account broker, Commodity commodity, String ref,
            List<Lot> open, Price price, Money commission) {
        int count = 1 + Math.floorMod(intent.percent(), open.size());
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
        return new Sell(date, broker, ref, Fixtures.sourceRow(ref), commodity,
                total, price, commission, LotSelectionMethod.SPECIFIC_LOT, named);
    }

    private boolean applyFee(Intent intent, LocalDate date, Account broker, String ref) {
        Money amount = Money.of(intent.amount(), Fixtures.USD);
        if (!amount.isPositive()) {
            return skip();
        }
        record(new Fee(date, broker, ref, Fixtures.sourceRow(ref),
                Account.of("Expenses:Fees:Account"), amount));
        addCash(broker, amount.negate());
        return true;
    }

    /**
     * Only paid on a commodity the account actually holds. A dividend on a position that
     * was never held is a break rather than a transaction, and breaks are week 4.
     */
    private boolean applyDividend(Intent intent, LocalDate date, Account broker, Commodity commodity, String ref) {
        if (!ledger.state().position(LedgerAccounts.holding(broker, commodity), commodity).isPositive()) {
            return skip();
        }
        Money gross = Money.of(intent.amount(), Fixtures.USD);
        if (!gross.isPositive()) {
            return skip();
        }
        Money withheld = Money.round(gross.toMajorUnits()
                .multiply(BigDecimal.valueOf(intent.percent()))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_EVEN), Fixtures.USD);
        if (withheld.compareTo(gross) > 0) {
            withheld = gross;
        }

        record(new CashDividend(date, broker, ref, Fixtures.sourceRow(ref), commodity, gross, withheld));
        addCash(broker, gross.minus(withheld));
        return true;
    }

    private boolean applyCashTransfer(Intent intent, LocalDate date, Account from, Account to, String ref) {
        Money available = ledger.state().cash(LedgerAccounts.cash(from), Fixtures.USD);
        if (!available.isPositive()) {
            return skip();
        }
        Money amount = Money.of(intent.amount(), Fixtures.USD);
        if (amount.compareTo(available) > 0) {
            amount = available;
        }
        if (!amount.isPositive()) {
            return skip();
        }

        record(new Transfer(date, from, to, ref, Fixtures.sourceRow(ref), Commodity.of(Fixtures.USD),
                Quantity.of(amount.toMajorUnits()), LotSelectionMethod.FIFO));
        addCash(from, amount.negate());
        addCash(to, amount);
        return true;
    }

    private boolean applySecurityTransfer(Intent intent, LocalDate date, Account from, Account to,
            Commodity commodity, String ref) {
        Account holding = LedgerAccounts.holding(from, commodity);
        if (ledger.state().openLots(holding, commodity).isEmpty()) {
            return skip();
        }
        Quantity quantity = fractionOf(ledger.state().position(holding, commodity), intent.percent());
        if (!quantity.isPositive()) {
            return skip();
        }
        // Specific lot is illegal on a transfer: the event has nowhere to name lots.
        LotSelectionMethod method = intent.method() == LotSelectionMethod.SPECIFIC_LOT
                ? LotSelectionMethod.FIFO
                : intent.method();

        record(new Transfer(date, from, to, ref, Fixtures.sourceRow(ref), commodity, quantity, method));
        // No cash changes hands, which is exactly what stops a transfer being read as a sale.
        return true;
    }

    /**
     * A forward or reverse split on a position the account actually holds.
     *
     * <p>Both directions are generated, because a reverse split is where the arithmetic
     * gets interesting: it can shrink a lot until its restated quantity rounds away
     * entirely, which is the path that turns basis into a rounding residue.
     */
    private boolean applySplit(Intent intent, LocalDate date, Account broker, Commodity commodity, String ref) {
        Account holding = LedgerAccounts.holding(broker, commodity);
        if (!ledger.state().position(holding, commodity).isPositive()) {
            return skip();
        }
        long ratio = splitRatio(intent.percent());
        LedgerEvent split = intent.percent() % 2 == 0
                ? new Split(date, broker, ref, Fixtures.sourceRow(ref), commodity, ratio, 1)
                : new ReverseSplit(date, broker, ref, Fixtures.sourceRow(ref), commodity, 1, ratio);

        record(split);
        // A split settles nothing, so no cash expectation moves.
        return true;
    }

    /** 2 through 5, so both directions stay in a range a real issuer might announce. */
    public static long splitRatio(int percent) {
        return 2L + Math.floorMod(percent, 4);
    }

    /**
     * Appends an event that must not move cash, keeping the independent cash expectation
     * valid. Verified rather than trusted: if the event does move cash, this fails loudly
     * instead of quietly invalidating invariant 6.
     */
    public Transaction recordNonCashEvent(LedgerEvent event) {
        Map<Account, Money> before = cashBalances();
        Transaction transaction = ledger.record(event);
        recorded.add(transaction);
        applied++;
        Map<Account, Money> after = cashBalances();
        if (!before.equals(after)) {
            throw new IllegalStateException(event.type() + " moved cash from " + before + " to " + after
                    + ", so it cannot be appended without updating the cash expectation");
        }
        return transaction;
    }

    private Map<Account, Money> cashBalances() {
        Map<Account, Money> balances = new LinkedHashMap<>();
        for (Account cash : expectedCash.keySet()) {
            balances.put(cash, ledger.state().cash(cash, Fixtures.USD));
        }
        return balances;
    }

    private static Quantity fractionOf(Quantity held, int percent) {
        return Quantity.of(held.value()
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), Quantity.SCALE, RoundingMode.HALF_EVEN));
    }

    private void addCash(Account broker, Money delta) {
        Account cash = LedgerAccounts.cash(broker);
        expectedCash.merge(cash, delta, Money::plus);
    }

    private boolean skip() {
        skipped++;
        return false;
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

    /** Expected cash per cash account, accumulated from the intents alone. */
    public Map<Account, Money> expectedCash() {
        return Map.copyOf(expectedCash);
    }

    public List<ExpectedSale> expectedSales() {
        return List.copyOf(expectedSales);
    }

    public int applied() {
        return applied;
    }

    public int skipped() {
        return skipped;
    }
}
