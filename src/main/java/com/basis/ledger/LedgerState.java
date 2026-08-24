package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.LotId;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.TxnId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The derived state: positions, lots and realized gains.
 *
 * <p>Holds nothing that is not recomputable from postings, and is only ever advanced by
 * applying postings. That is what makes it disposable, and it is why the same class
 * serves both the in memory ledger and the database projector: one projection
 * algorithm, two sinks. If the projector had its own copy of this arithmetic, the two
 * could disagree and invariant 7 would be testing the wrong thing.
 *
 * <p>Not thread safe. A ledger is replayed by one writer.
 */
public final class LedgerState implements LotBook {

    private final Map<PositionKey, Quantity> positions = new TreeMap<>();
    private final Map<LotId, Lot> lots = new LinkedHashMap<>();
    private final List<RealizedGain> realizedGains = new ArrayList<>();

    /** Advances the state by one transaction, reading only its postings. */
    public void apply(Transaction transaction) {
        applyPostings(transaction.id(), transaction.date(), transaction.postings());
    }

    /**
     * Advances the state by one transaction's postings, in the given order. This is the
     * entry point replay uses, where there is no {@code Transaction} object left, only
     * rows.
     */
    public void applyPostings(TxnId txnId, LocalDate date, List<Posting> postings) {
        for (Posting posting : postings) {
            applyPosting(posting);
        }
        recordRealizedGain(txnId, date, postings);
    }

    private void applyPosting(Posting posting) {
        movePosition(posting.account(), posting.commodity(), posting.quantity());
        if (!posting.hasCost() || posting.quantity().isZero()) {
            return;
        }
        LotId lotId = posting.cost().lotId();
        if (posting.quantity().isPositive()) {
            openLot(posting, lotId);
        } else {
            consumeLot(posting, lotId);
        }
    }

    private void openLot(Posting posting, LotId lotId) {
        Lot existing = lots.get(lotId);
        if (existing != null) {
            throw new IllegalStateException("lot " + lotId + " is already open with "
                    + existing.remainingQuantity() + " " + existing.commodity()
                    + ". A lot is opened by exactly one acquisition.");
        }
        lots.put(lotId, Lot.opened(lotId, posting.account(), posting.commodity(),
                posting.cost().acquisitionDate(), posting.cost().unitCost(), posting.quantity()));
    }

    private void consumeLot(Posting posting, LotId lotId) {
        Lot lot = lots.get(lotId);
        if (lot == null) {
            throw new IllegalStateException("posting disposes of lot " + lotId
                    + " which was never opened. The ledger cannot dispose of basis it never acquired.");
        }
        lots.put(lotId, lot.consume(posting.quantity().negate()));
    }

    /**
     * A position that reaches zero is removed rather than kept as a zero row.
     *
     * <p>An absent position and a zero position mean the same thing to every reader, and
     * dropping it keeps the derived position table to the holdings that actually exist.
     */
    private void movePosition(Account account, Commodity commodity, Quantity delta) {
        PositionKey key = new PositionKey(account, commodity);
        Quantity moved = positions.getOrDefault(key, Quantity.ZERO).plus(delta);
        if (moved.isZero()) {
            positions.remove(key);
        } else {
            positions.put(key, moved);
        }
    }

    /**
     * Reads the realized gain back out of a transaction that disposed of something.
     *
     * <p>Nothing is computed here that the postings do not already say. The gain is the
     * negation of what landed on the plug account, the basis is the negation of the
     * disposal legs, and the proceeds are everything else, which for a sale is the cash
     * leg plus the expensed commission. Three numbers from three different subsets of the
     * postings, so the identity between them is a real statement.
     *
     * <p>Not every disposal realizes anything. See {@link #isSettled(List)}.
     */
    private void recordRealizedGain(TxnId txnId, LocalDate date, List<Posting> postings) {
        List<Posting> disposals = postings.stream()
                .filter(LedgerState::isDisposal)
                .toList();
        if (disposals.isEmpty() || !isSettled(postings)) {
            return;
        }

        Currency currency = disposals.get(0).weight().currency();
        Account account = disposals.get(0).account();
        Commodity commodity = disposals.get(0).commodity();
        Quantity quantity = Quantity.ZERO;
        Money basis = Money.zero(currency);
        for (Posting disposal : disposals) {
            if (!disposal.commodity().equals(commodity) || !disposal.account().equals(account)) {
                throw new IllegalStateException("a single transaction disposed of " + commodity + " in "
                        + account + " and " + disposal.commodity() + " in " + disposal.account()
                        + ". Week 1 events dispose of one holding at a time.");
            }
            quantity = quantity.plus(disposal.quantity().negate());
            basis = basis.minus(disposal.weight());
        }

        Money gain = Money.zero(currency);
        Money proceeds = Money.zero(currency);
        for (Posting posting : postings) {
            if (isDisposal(posting)) {
                continue;
            }
            if (LedgerAccounts.isRealizedGain(posting.account())) {
                gain = gain.minus(posting.weight());
            } else {
                proceeds = proceeds.plus(posting.weight());
            }
        }

        realizedGains.add(new RealizedGain(txnId, date, account, commodity, quantity, proceeds, basis, gain));
    }

    private static boolean isDisposal(Posting posting) {
        return posting.hasCost() && posting.quantity().isNegative();
    }

    /**
     * Whether a disposal was settled, and therefore realized something.
     *
     * <p>A disposal only realizes a gain when it was settled: paid for in cash, or booked
     * against the realized gain account. A transaction that moves shares out of one
     * account and into another disposes of lots without settling anything, and reporting
     * a realized gain for it would invent a taxable event out of paperwork.
     *
     * <p>Stated in terms of postings rather than event types, because the projector rebuilds
     * this from the posting table where no event survives. It generalises correctly to the
     * corporate actions in week 3: a split settles nothing and realizes nothing, while a
     * reverse split that pays cash in lieu of a fractional share settles that fraction in
     * cash and does realize a gain on it, which is exactly the treatment the IRS expects
     * and exactly the one a broker statement tends not to spell out.
     *
     * <p>A realized gain leg counts as settlement too. That matters for a disposal whose
     * proceeds round to nothing in whole minor units: it still books a loss equal to its
     * basis, and it still has to be reported.
     *
     * <p>"Cash moved" is not enough on its own, and a corporate action is what proves it.
     * A split books its unavoidable rounding residue to an equity account in the ledger's
     * own currency, which is a cash posting by any structural definition and settles
     * nothing at all. So the test is narrower: cash has to have reached an account that
     * actually holds a cash balance, or a gain has to have been booked.
     *
     * <p>Known limit. A securities transfer that also charged a fee would move cash into
     * a real cash account while settling nothing, and would be misread as a sale.
     * Unreachable today because no transfer event carries a fee. See
     * docs/ARCHITECTURE.md section 16.
     */
    private static boolean isSettled(List<Posting> postings) {
        for (Posting posting : postings) {
            if (!posting.isCash()) {
                continue;
            }
            if (LedgerAccounts.isRealizedGain(posting.account())
                    || LedgerAccounts.isCashBalance(posting.account())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Lot> openLots(Account account, Commodity commodity) {
        return lots.values().stream()
                .filter(Lot::isOpen)
                .filter(lot -> lot.account().equals(account) && lot.commodity().equals(commodity))
                .sorted(Comparator.comparing(Lot::acquisitionDate).thenComparing(lot -> lot.id().value()))
                .toList();
    }

    /** Every lot ever opened, open or closed, in lot id order. */
    public List<Lot> allLots() {
        return lots.values().stream()
                .sorted(Comparator.comparing(lot -> lot.id().value()))
                .toList();
    }

    /** Net quantity held, zero if the holding is unknown. */
    public Quantity position(Account account, Commodity commodity) {
        return positions.getOrDefault(new PositionKey(account, commodity), Quantity.ZERO);
    }

    /** Every non zero position, in account then commodity order. */
    public Map<PositionKey, Quantity> positions() {
        return Collections.unmodifiableMap(new TreeMap<>(positions));
    }

    /** Realized gains in the order they were realized. */
    public List<RealizedGain> realizedGains() {
        return List.copyOf(realizedGains);
    }

    /** Cash held in an account, zero if none. */
    public Money cash(Account account, Currency currency) {
        Quantity held = position(account, Commodity.of(currency));
        return Money.of(held.value(), currency);
    }

    /** Total basis still held in a holding: the sum over open lots of quantity times unit cost. */
    public Money openBasis(Account account, Commodity commodity, Currency currency) {
        Money total = Money.zero(currency);
        for (Lot lot : openLots(account, commodity)) {
            if (lot.unitCost().currency().equals(currency)) {
                total = total.plus(lot.remainingBasis());
            }
        }
        return total;
    }
}
