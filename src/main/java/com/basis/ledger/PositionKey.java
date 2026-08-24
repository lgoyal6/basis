package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import java.util.Comparator;
import java.util.Objects;

/** Identifies a holding: one commodity in one account. */
public record PositionKey(Account account, Commodity commodity) implements Comparable<PositionKey> {

    private static final Comparator<PositionKey> ORDER =
            Comparator.comparing(PositionKey::account).thenComparing(PositionKey::commodity);

    public PositionKey {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(commodity, "commodity");
    }

    @Override
    public int compareTo(PositionKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return account + " " + commodity;
    }
}
