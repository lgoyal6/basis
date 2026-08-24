package com.basis.persistence;

import com.basis.domain.Posting;
import com.basis.domain.TxnId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One row of the posting table, with the transaction context replay needs.
 *
 * @param id the replay order. Derived state is rebuilt by reading these in id order.
 * @param storedWeightMinor the weight as it was written, kept alongside the posting so
 *     that a read back can be checked against a freshly computed
 *     {@link Posting#weight()}. See docs/ARCHITECTURE.md section 13.
 */
public record PostingRow(
        long id,
        TxnId txnId,
        LocalDate date,
        int ordinal,
        Posting posting,
        long storedWeightMinor) {

    public PostingRow {
        Objects.requireNonNull(txnId, "txnId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(posting, "posting");
    }

    /** True when the stored weight still agrees with what the code computes today. */
    public boolean weightAgreesWithCode() {
        return posting.weight().minorUnits() == storedWeightMinor;
    }
}
