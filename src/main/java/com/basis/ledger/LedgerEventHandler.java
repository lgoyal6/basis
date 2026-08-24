package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Cost;
import com.basis.domain.Lot;
import com.basis.domain.LotId;
import com.basis.domain.Money;
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
import java.util.List;

/**
 * Turns an event into a balanced transaction. The only place in the system where
 * arithmetic on a trade happens.
 *
 * <p>Dispatch is an exhaustive switch over the sealed hierarchy, so a new corporate
 * action cannot be added without this class refusing to compile. The events week 1 does
 * not own are refused loudly at runtime rather than quietly mishandled.
 */
public final class LedgerEventHandler {

    /**
     * @param lots open lots to draw a disposal from, read only
     * @throws UnsupportedOperationException for events a later week owns
     */
    public Transaction toTransaction(LedgerEvent event, LotBook lots) {
        return switch (event) {
            case Buy buy -> acquire(buy);
            case Sell sell -> dispose(sell, lots);
            case Fee fee -> charge(fee);
            case OpeningBalance opening -> open(opening);

            // Declared, not handled. Each names the week that owns it, so the failure
            // says what to do about it rather than only that something is missing.
            case CashDividend event2 -> notYet(event2, 2, "cash distributions and withholding");
            case Transfer event2 -> notYet(event2, 2, "carrying lots across accounts");
            case StockDividend event2 -> notYet(event2, 3, "spreading basis across a larger share count");
            case Split event2 -> notYet(event2, 3, "corporate actions");
            case ReverseSplit event2 -> notYet(event2, 3, "corporate actions and cash in lieu of fractions");
            case SpinOff event2 -> notYet(event2, 3, "corporate actions and issuer published basis allocation");
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

    private static Transaction notYet(LedgerEvent event, int week, String what) {
        throw new UnsupportedOperationException(event.type() + " is declared but not handled in week 1."
                + " It needs " + what + ", which is week " + week + " work."
                + " The event on " + event.date() + " in " + event.account() + " was not applied.");
    }
}
