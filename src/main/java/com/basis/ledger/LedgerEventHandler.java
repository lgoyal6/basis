package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Cost;
import com.basis.domain.IdempotencyKey;
import com.basis.domain.Lot;
import com.basis.domain.LotId;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.event.Buy;
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.Fee;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.OpeningBalance;
import com.basis.domain.event.ReverseSplit;
import com.basis.domain.event.Sell;
import com.basis.domain.event.SpinOff;
import com.basis.domain.event.Split;
import com.basis.domain.event.StockDividend;
import com.basis.domain.event.Transfer;
import com.basis.ledger.lot.LotConsumption;
import com.basis.ledger.lot.LotSelectionRequest;
import com.basis.ledger.lot.LotSelectionStrategies;
import com.basis.ledger.lot.LotSelectionStrategy;
import java.util.Currency;
import java.util.List;

/**
 * Turns an event into a balanced transaction. The only place in the system where
 * arithmetic on a trade happens.
 *
 * <p>Dispatch is an exhaustive switch over the sealed hierarchy, so a new event cannot be
 * added without this class refusing to compile. Every event the hierarchy declares is now
 * handled, which means the switch has no default and no escape hatch: the next corporate
 * action anyone adds will break the build here until someone decides what it does.
 */
public final class LedgerEventHandler {

    /** @param lots open lots to draw a disposal, transfer or corporate action from, read only */
    public Transaction toTransaction(LedgerEvent event, LotBook lots) {
        return switch (event) {
            case Buy buy -> acquire(buy);
            case Sell sell -> dispose(sell, lots);
            case Fee fee -> charge(fee);
            case OpeningBalance opening -> open(opening);
            case CashDividend dividend -> distribute(dividend);
            case Transfer transfer -> move(transfer, lots);
            case Split split -> restate(split, split.commodity(), split.numerator(), split.denominator(), lots);
            case ReverseSplit split ->
                    restate(split, split.commodity(), split.numerator(), split.denominator(), lots);
            case StockDividend dividend -> restateForStockDividend(dividend, lots);
            case SpinOff spinOff -> allocate(spinOff, lots);
        };
    }

    /**
     * An acquisition opens one lot and pays for it from cash.
     *
     * <p>The lot id is derived from the event's idempotency key rather than generated, so
     * replaying an import reproduces lot ids exactly. A random id here would make the
     * derived state hash differ on every rebuild and invariant 7 unprovable.
     */
    private Transaction acquire(Buy buy) {
        Account holding = LedgerAccounts.holding(buy.account(), buy.commodity());
        Cost cost = new Cost(lotIdFor(buy), buy.price(), buy.date());

        return TransactionBuilder.forEvent(buy)
                .narration("Buy " + buy.quantity() + " " + buy.commodity() + " at " + buy.price())
                .security(holding, buy.commodity(), buy.quantity(), cost)
                .cash(LedgerAccounts.COMMISSIONS, buy.commission())
                .plugAt(LedgerAccounts.cash(buy.account()), buy.price().currency());
    }

    /**
     * A disposal consumes lots and realizes whatever the balance requirement says.
     *
     * <p>One posting per consumed lot, each pinned to the lot it came from, so a
     * multi lot sale is auditable lot by lot rather than as a single blended number.
     * The gain is the plug: this method never computes it.
     */
    private Transaction dispose(Sell sell, LotBook lots) {
        Account holding = LedgerAccounts.holding(sell.account(), sell.commodity());
        List<Lot> open = lots.openLots(holding, sell.commodity());
        LotSelectionStrategy strategy = LotSelectionStrategies.forMethod(sell.method());
        List<LotConsumption> consumed = strategy.select(new LotSelectionRequest(
                holding, sell.commodity(), sell.quantity(), open, sell.specificLots()));

        TransactionBuilder builder = TransactionBuilder.forEvent(sell)
                .narration("Sell " + sell.quantity() + " " + sell.commodity() + " at " + sell.price()
                        + " (" + sell.method() + ")");
        for (LotConsumption consumption : consumed) {
            builder.security(holding, sell.commodity(),
                    consumption.quantity().negate(), consumption.lot().cost());
        }

        // Cash receives the gross consideration less the commission, and the commission
        // is expensed. See docs/ARCHITECTURE.md section 3: the mandate's worked example
        // books the lot at the clean trade price, so fees are not capitalised.
        Money net = sell.grossProceeds().minus(sell.commission());
        builder.cash(LedgerAccounts.cash(sell.account()), net);
        builder.cash(LedgerAccounts.COMMISSIONS, sell.commission());

        return builder.plugAt(LedgerAccounts.REALIZED_GAINS, sell.price().currency());
    }

    /**
     * A cash distribution: income credited, tax withheld at source expensed, and the net
     * landing in cash as the plug.
     *
     * <p>Gross and withheld stay separate legs rather than being netted into one, because
     * a reconciliation that cannot see the withholding cannot explain why the cash the
     * broker paid is smaller than the dividend the issuer declared.
     */
    private Transaction distribute(CashDividend dividend) {
        return TransactionBuilder.forEvent(dividend)
                .narration("Dividend " + dividend.grossAmount() + " from " + dividend.commodity()
                        + (dividend.withheldAmount().isZero()
                                ? "" : " less " + dividend.withheldAmount() + " withheld"))
                .cash(LedgerAccounts.dividendIncome(dividend.commodity()), dividend.grossAmount().negate())
                .cash(LedgerAccounts.WITHHOLDING_TAX, dividend.withheldAmount())
                .plugAt(LedgerAccounts.cash(dividend.account()), dividend.grossAmount().currency());
    }

    /**
     * Movement between accounts. Nothing is bought, sold or realized.
     *
     * <p>Cash is the easy half. Securities are the reason this is not a week 1 event: the
     * lots have to arrive in the receiving account carrying the acquisition date and unit
     * cost they left with, or the holding period restarts and every later disposal reports
     * a short term gain that was actually long term.
     *
     * <p>The receiving lot needs its own identifier, because a lot is opened by exactly one
     * acquisition and the outgoing lot still exists in the sending account's history. The
     * new id is derived by hashing the event key together with the source lot id, so it is
     * stable across replays, fixed in length however many times a position is transferred,
     * and traceable back to where it came from.
     */
    private Transaction move(Transfer transfer, LotBook lots) {
        if (transfer.commodity().isCash()) {
            return moveCash(transfer);
        }
        return moveSecurity(transfer, lots);
    }

    private Transaction moveCash(Transfer transfer) {
        Money amount = Money.of(transfer.quantity().value(), transfer.commodity().asCurrency());
        return TransactionBuilder.forEvent(transfer)
                .narration("Transfer " + amount + " from " + transfer.fromAccount()
                        + " to " + transfer.toAccount())
                .cash(LedgerAccounts.cash(transfer.toAccount()), amount)
                .plugAt(LedgerAccounts.cash(transfer.fromAccount()), amount.currency());
    }

    private Transaction moveSecurity(Transfer transfer, LotBook lots) {
        Account from = LedgerAccounts.holding(transfer.fromAccount(), transfer.commodity());
        Account to = LedgerAccounts.holding(transfer.toAccount(), transfer.commodity());
        LotSelectionStrategy strategy = LotSelectionStrategies.forMethod(transfer.method());
        List<LotConsumption> moved = strategy.select(new LotSelectionRequest(
                from, transfer.commodity(), transfer.quantity(),
                lots.openLots(from, transfer.commodity()), List.of()));

        TransactionBuilder builder = TransactionBuilder.forEvent(transfer)
                .narration("Transfer " + transfer.quantity() + " " + transfer.commodity()
                        + " from " + transfer.fromAccount() + " to " + transfer.toAccount()
                        + " (" + transfer.method() + ")");
        for (LotConsumption consumption : moved) {
            Lot source = consumption.lot();
            builder.security(from, transfer.commodity(), consumption.quantity().negate(), source.cost());
            builder.security(to, transfer.commodity(), consumption.quantity(),
                    new Cost(receivingLotId(transfer, source.id()), source.unitCost(), source.acquisitionDate()));
        }
        // Both sides weigh the same at cost, so the residual is already zero and the plug
        // emits nothing. A transfer settles no cash, which is what stops it from being
        // read as a disposal. See LedgerState.isSettled.
        return builder.plugAt(LedgerAccounts.cash(transfer.toAccount()), unitCurrency(moved));
    }

    private static Currency unitCurrency(List<LotConsumption> moved) {
        return moved.get(0).lot().unitCost().currency();
    }

    /** Stable across replays, fixed length, and traceable to the lot it came from. */
    private static LotId receivingLotId(Transfer transfer, LotId source) {
        return LotId.of(IdempotencyKey.of(
                transfer.idempotencyKey().toString(), source.value()).toString());
    }

    /**
     * A share count restatement: a split, a reverse split, or a stock dividend.
     *
     * <p>Every open lot is disposed of and reopened at the new count with a restated unit
     * cost and its original acquisition date. Nothing settles in cash, so nothing is
     * realized, which is what {@code LedgerState.isSettled} is for.
     *
     * <p>The plug is the rounding residue account, not cash and not a gain. In the
     * ordinary case the residual is zero and no plug posting is emitted at all.
     */
    private Transaction restate(LedgerEvent event, Commodity commodity, long numerator, long denominator,
            LotBook lots) {
        Account holding = LedgerAccounts.holding(event.account(), commodity);
        List<Lot> open = lots.openLots(holding, commodity);
        List<Posting> postings = Relotting.restate(event, holding, commodity, open, numerator, denominator);

        TransactionBuilder builder = TransactionBuilder.forEvent(event)
                .narration(event.type() + " " + numerator + " for " + denominator + " on " + commodity);
        for (Posting posting : postings) {
            builder.posting(posting);
        }
        return builder.plugAt(LedgerAccounts.ROUNDING, currencyOf(open));
    }

    /**
     * A stock dividend is a split whose ratio is implied by the shares received.
     *
     * <p>Receiving 2 shares on a holding of 8 is a 10 for 8 restatement. Expressing it
     * that way rather than as "open a new lot for the free shares" is what keeps the total
     * basis unchanged: free shares do not add basis, they dilute the basis already there.
     */
    private Transaction restateForStockDividend(StockDividend dividend, LotBook lots) {
        Account holding = LedgerAccounts.holding(dividend.account(), dividend.commodity());
        Quantity held = positionOf(lots.openLots(holding, dividend.commodity()));
        if (!held.isPositive()) {
            throw new IllegalStateException("stock dividend of " + dividend.quantity() + " "
                    + dividend.commodity() + " in " + holding + " but nothing is held there."
                    + " A distribution on a position that was never held is a break, not a transaction.");
        }
        if (!dividend.quantity().isPositive()) {
            throw new IllegalArgumentException("stock dividend quantity must be positive, was "
                    + dividend.quantity());
        }

        // Scaled to whole units so the ratio is exact in longs: a holding of 8.5 receiving
        // 1.5 becomes 1000000000 for 850000000 rather than a rounded decimal ratio.
        long denominator = unscaled(held);
        long numerator = Math.addExact(denominator, unscaled(dividend.quantity()));
        return restate(dividend, dividend.commodity(), numerator, denominator, lots);
    }

    private static long unscaled(Quantity quantity) {
        return quantity.value().movePointRight(Quantity.SCALE).longValueExact();
    }

    private static Quantity positionOf(List<Lot> lots) {
        Quantity total = Quantity.ZERO;
        for (Lot lot : lots) {
            total = total.plus(lot.remainingQuantity());
        }
        return total;
    }

    private static Currency currencyOf(List<Lot> lots) {
        if (lots.isEmpty()) {
            throw new IllegalStateException("no open lots, so there is no currency to balance in");
        }
        return lots.get(0).unitCost().currency();
    }

    /**
     * A spin off: the parent distributes shares of a new company, and part of the parent's
     * cost basis goes with them.
     *
     * <p>The allocation fraction comes from the event because the issuer publishes it and
     * no price feed can derive it. That is why this is the corporate action most likely to
     * raise a break and ask rather than guess.
     *
     * <p>Nothing settles in cash, so nothing is realized, and the spun off shares inherit
     * the parent lot's acquisition date.
     */
    private Transaction allocate(SpinOff spinOff, LotBook lots) {
        Account parentHolding = LedgerAccounts.holding(spinOff.account(), spinOff.parent());
        Account spunHolding = LedgerAccounts.holding(spinOff.account(), spinOff.spunOff());
        List<Lot> open = lots.openLots(parentHolding, spinOff.parent());
        List<Posting> postings = Relotting.spinOff(spinOff, parentHolding, spunHolding, open);

        TransactionBuilder builder = TransactionBuilder.forEvent(spinOff)
                .narration("SpinOff " + spinOff.spunOff() + " from " + spinOff.parent() + ", "
                        + spinOff.parentBasisFraction().toPlainString() + " of basis allocated");
        for (Posting posting : postings) {
            builder.posting(posting);
        }
        return builder.plugAt(LedgerAccounts.ROUNDING, currencyOf(open));
    }

    private Transaction charge(Fee fee) {
        return TransactionBuilder.forEvent(fee)
                .narration("Fee " + fee.amount() + " to " + fee.expenseAccount())
                .cash(fee.expenseAccount(), fee.amount())
                .plugAt(LedgerAccounts.cash(fee.account()), fee.amount().currency());
    }

    /**
     * An opening balance is plugged to {@code Equity:Opening-Balances}, which is what
     * keeps the ledger closed even though the money came from before the history starts.
     */
    private Transaction open(OpeningBalance opening) {
        if (opening.isCash()) {
            Money amount = Money.of(opening.quantity().value(), opening.commodity().asCurrency());
            return TransactionBuilder.forEvent(opening)
                    .narration("Opening balance " + amount)
                    .cash(LedgerAccounts.cash(opening.account()), amount)
                    .plugAt(LedgerAccounts.OPENING_BALANCES, amount.currency());
        }

        Account holding = LedgerAccounts.holding(opening.account(), opening.commodity());
        Cost cost = new Cost(lotIdFor(opening), opening.unitCost(), opening.date());
        return TransactionBuilder.forEvent(opening)
                .narration("Opening balance " + opening.quantity() + " " + opening.commodity()
                        + " at " + opening.unitCost())
                .security(holding, opening.commodity(), opening.quantity(), cost)
                .plugAt(LedgerAccounts.OPENING_BALANCES, opening.unitCost().currency());
    }

    /** Deterministic, so replaying an import reproduces the same lot ids. */
    private static LotId lotIdFor(LedgerEvent event) {
        return LotId.of(event.idempotencyKey().toString());
    }
}
