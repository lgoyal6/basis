package com.basis.reconcile;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One disagreement between what basis computed and what the broker reported, with a
 * probable cause attached.
 *
 * <p>Not derived state. Once a human has triaged a break, their judgement is not
 * recomputable from the posting table, which is why {@code break_record} survives a
 * truncate and replay while {@code position} and {@code lot} do not.
 *
 * @param brokerQuantity what the broker reported, zero when it reported nothing
 * @param computedQuantity what basis computed, zero when it holds nothing
 */
public record BreakRecord(
        LocalDate asOf,
        Account account,
        Commodity commodity,
        BreakType type,
        Quantity brokerQuantity,
        Quantity computedQuantity,
        Money brokerAmount,
        Money computedAmount,
        ProbableCause cause,
        BreakStatus status) {

    public BreakRecord {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(brokerQuantity, "brokerQuantity");
        Objects.requireNonNull(computedQuantity, "computedQuantity");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(status, "status");
        // An identity mismatch is exempt: a renamed security can agree on every number and
        // still be a break, because what the two sides disagree about is what it is called.
        if (type != BreakType.IDENTITY_MISMATCH
                && brokerQuantity.equals(computedQuantity) && brokerAmount == null && computedAmount == null) {
            throw new IllegalArgumentException("a break has to disagree about something, but "
                    + commodity + " in " + account + " agrees on quantity and states no amounts");
        }
    }

    /** How far apart the two sides are, positive when the broker reports more. */
    public Quantity quantityDifference() {
        return brokerQuantity.minus(computedQuantity);
    }

    @Override
    public String toString() {
        return asOf + " " + account + " " + commodity + " " + type
                + ": broker " + brokerQuantity + ", computed " + computedQuantity + ". " + cause;
    }
}
