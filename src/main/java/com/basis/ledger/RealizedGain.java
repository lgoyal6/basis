package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import com.basis.domain.TxnId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A realized gain or loss, read back out of a transaction rather than computed into it.
 *
 * <p>{@code gain} is the negation of what landed on the plug account.
 * {@code basis} is the negation of the disposal legs' weights. {@code proceeds} is the
 * gross consideration, the cash leg plus the expensed commission, computed from a
 * different subset of the postings than the other two. That separation is deliberate:
 * if any of the three were defined in terms of the others, invariant 5 would be a
 * tautology and would detect nothing.
 */
public record RealizedGain(
        TxnId txnId,
        LocalDate date,
        Account account,
        Commodity commodity,
        Quantity quantity,
        Money proceeds,
        Money basis,
        Money gain) {

    public RealizedGain {
        Objects.requireNonNull(txnId, "txnId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(proceeds, "proceeds");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(gain, "gain");
    }

    /** Invariant 5, stated on the record that carries all three numbers. */
    public boolean satisfiesProceedsIdentity() {
        return proceeds.minus(basis).equals(gain);
    }
}
