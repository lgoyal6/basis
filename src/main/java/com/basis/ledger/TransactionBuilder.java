package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Cost;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.TxnId;
import com.basis.domain.event.LedgerEvent;
import java.util.Currency;

/**
 * Builds a transaction from its known postings plus exactly one plug.
 *
 * <p>The plug is the whole point. A caller names the postings it knows and one account
 * to absorb the remainder; the builder computes that remainder from the balance
 * requirement. For a disposal the plug is {@code Income:CapitalGains:Realized}, which
 * is why there is no code anywhere that computes a realized gain. One invariant, one
 * arithmetic, nothing that can disagree with itself.
 *
 * <p>A plug of zero emits no posting: a wash sale simply has no gain leg.
 */
public final class TransactionBuilder {

    private TransactionBuilder() {
    }

    /** Starts a transaction that inherits its date, key and source row from the event. */
    public static TransactionBuilder forEvent(LedgerEvent event) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Overrides the generated transaction id. Used by replay, not by import. */
    public TransactionBuilder id(TxnId id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public TransactionBuilder narration(String narration) {
        throw new UnsupportedOperationException("not implemented");
    }

    public TransactionBuilder posting(Posting posting) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** A cash leg. Skipped when the amount is zero, so a zero commission adds no noise. */
    public TransactionBuilder cash(Account account, Money amount) {
        throw new UnsupportedOperationException("not implemented");
    }

    public TransactionBuilder security(Account account, Commodity commodity, Quantity quantity, Cost cost) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Closes the transaction by sending the residual in {@code currency} to
     * {@code plugAccount}, then verifies the result balances in every currency.
     *
     * @throws UnbalancedTransactionException if a residual remains in another currency,
     *     which means the caller tried to balance two currencies with one plug
     */
    public Transaction plugAt(Account plugAccount, Currency currency) {
        throw new UnsupportedOperationException("not implemented");
    }
}
