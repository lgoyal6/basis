package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Cost;
import com.basis.domain.IdempotencyKey;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.TxnId;
import com.basis.domain.event.LedgerEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Builds a transaction from its known postings plus exactly one plug.
 *
 * <p>The plug is the whole point. A caller names the postings it knows and one account
 * to absorb the remainder; the builder computes that remainder from the balance
 * requirement. For a disposal the plug is {@code Income:CapitalGains:Realized}, which
 * is why there is no code anywhere that computes a realized gain. One invariant, one
 * arithmetic, nothing that can disagree with itself.
 *
 * <p>A plug of zero emits no posting, so a wash sale has no gain leg, unless the plug is
 * the only thing that would state the other side of the transaction. See
 * docs/ARCHITECTURE.md section 14.
 */
public final class TransactionBuilder {

    private final LocalDate date;
    private final String eventType;
    private final IdempotencyKey idempotencyKey;
    private final String sourceRow;
    private final List<Posting> postings = new ArrayList<>();

    private TxnId id = TxnId.random();
    private String narration = "";

    private TransactionBuilder(LedgerEvent event) {
        this.date = event.date();
        this.eventType = event.type();
        this.idempotencyKey = event.idempotencyKey();
        this.sourceRow = event.sourceRow();
    }

    /** Starts a transaction that inherits its date, key and source row from the event. */
    public static TransactionBuilder forEvent(LedgerEvent event) {
        Objects.requireNonNull(event, "event");
        return new TransactionBuilder(event);
    }

    /** Overrides the generated transaction id. Used by replay, not by import. */
    public TransactionBuilder id(TxnId id) {
        this.id = Objects.requireNonNull(id, "id");
        return this;
    }

    public TransactionBuilder narration(String narration) {
        this.narration = Objects.requireNonNull(narration, "narration");
        return this;
    }

    public TransactionBuilder posting(Posting posting) {
        postings.add(Objects.requireNonNull(posting, "posting"));
        return this;
    }

    /** A cash leg. Skipped when the amount is zero, so a zero commission adds no noise. */
    public TransactionBuilder cash(Account account, Money amount) {
        if (amount.isZero()) {
            return this;
        }
        return posting(Posting.cash(account, amount));
    }

    public TransactionBuilder security(Account account, Commodity commodity, Quantity quantity, Cost cost) {
        return posting(Posting.security(account, commodity, quantity, cost));
    }

    /**
     * Closes the transaction by sending the residual in {@code currency} to
     * {@code plugAccount}, then verifies the result balances in every currency.
     *
     * @throws UnbalancedTransactionException if a residual remains in another currency,
     *     which means the caller tried to balance two currencies with one plug
     */
    public Transaction plugAt(Account plugAccount, Currency currency) {
        Objects.requireNonNull(plugAccount, "plugAccount");
        Objects.requireNonNull(currency, "currency");

        Money residual = BalanceChecker.residual(postings, currency);
        if (residual.isZero() && postings.size() >= 2) {
            // Nothing to plug and both sides are already stated, so a 0.00 posting would
            // be noise: a wash sale gets no gain leg at all.
            //
            // When it is the only thing that would state the other side, the zero plug is
            // emitted. An acquisition can genuinely cost nothing, either because it was a
            // gift or a promotional grant, or because a tiny fractional quantity at a
            // sub cent unit price rounds to nothing at all in whole minor units. Dropping
            // the plug there would leave a one legged transaction, which is not a double
            // entry. See docs/ARCHITECTURE.md section 14.
            return build();
        }
        postings.add(Posting.cash(plugAccount, residual.negate()));
        return build();
    }

    private Transaction build() {
        Transaction transaction = new Transaction(
                id, date, eventType, narration, postings, idempotencyKey, sourceRow);
        // Verified here rather than trusted: the plug closes one currency, and this is
        // what catches a caller that quietly left another one open.
        BalanceChecker.requireBalanced(transaction);
        return transaction;
    }
}
