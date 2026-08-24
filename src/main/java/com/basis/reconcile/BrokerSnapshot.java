package com.basis.reconcile;

import com.basis.domain.Account;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Everything a broker says it is holding in one account, on one date.
 *
 * <p>The date belongs to the snapshot rather than to each position, because a statement is
 * a photograph of an account at a moment. Comparing positions dated differently against
 * one ledger state would produce breaks that are entirely the comparison's own fault.
 */
public record BrokerSnapshot(
        Account account, LocalDate asOf, SnapshotScope scope, List<BrokerPosition> positions) {

    public BrokerSnapshot {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(positions, "positions");
        positions = List.copyOf(positions);
        for (BrokerPosition position : positions) {
            if (!position.account().isUnder(account)) {
                throw new IllegalArgumentException("snapshot of " + account + " contains a position in "
                        + position.account());
            }
            if (!scope.covers(position.commodity())) {
                throw new IllegalArgumentException("snapshot is declared " + scope + " but carries a "
                        + position.commodity() + " balance. Declare the scope the statement actually had.");
            }
        }
    }

    /** A statement that lists securities and says nothing about cash, which is most of them. */
    public static BrokerSnapshot ofSecurities(Account account, LocalDate asOf, List<BrokerPosition> positions) {
        return new BrokerSnapshot(account, asOf, SnapshotScope.SECURITIES_ONLY, positions);
    }

    /** A statement that carries the cash balance too, so an omitted cash line means zero. */
    public static BrokerSnapshot complete(Account account, LocalDate asOf, List<BrokerPosition> positions) {
        return new BrokerSnapshot(account, asOf, SnapshotScope.SECURITIES_AND_CASH, positions);
    }
}
