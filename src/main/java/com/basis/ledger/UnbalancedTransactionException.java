package com.basis.ledger;

import com.basis.domain.Money;
import java.util.Comparator;
import java.util.Currency;
import java.util.Map;

/**
 * Thrown when a transaction's postings do not sum to zero at cost. This is the one
 * invariant nothing is allowed past: no unbalanced transaction reaches the database.
 */
public class UnbalancedTransactionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnbalancedTransactionException(String message) {
        super(message);
    }

    static UnbalancedTransactionException of(String context, Map<Currency, Money> residuals) {
        StringBuilder message = new StringBuilder(context).append(" does not balance at cost. Residual:");
        residuals.entrySet().stream()
                .filter(entry -> !entry.getValue().isZero())
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Currency::getCurrencyCode)))
                .forEach(entry -> message.append(' ').append(entry.getValue()));
        return new UnbalancedTransactionException(message.toString());
    }
}
