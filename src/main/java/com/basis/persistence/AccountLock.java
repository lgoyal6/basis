package com.basis.persistence;

import com.basis.domain.Account;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * One writer per account at a time.
 *
 * <p>Without this, two imports into the same account race in a way no constraint catches.
 * Recording an event hydrates the whole ledger from the posting table, decides which lots a
 * sale consumes from that snapshot, and appends the result. Two processes doing it at once both
 * hydrate the same state, both pick the same lots, and both succeed. The position ends up
 * over-disposed, every later gain is computed from a basis that was double counted, and nothing
 * anywhere failed. Lot quantities are derived state rather than a database column, so there is
 * no unique index that could have noticed.
 *
 * <p>A session level advisory lock rather than a transaction scoped one, deliberately. The
 * import is not a single transaction and should not become one: it writes a batch marker first
 * and clears it at the end precisely so that a crash leaves visible evidence rather than
 * silently vanishing, and that design predates this lock (see docs/ARCHITECTURE.md section 9).
 * Wrapping the whole import in one transaction to get {@code pg_advisory_xact_lock} would undo
 * it. A session lock spans exactly the work, on its own connection, and Postgres releases it
 * when that connection closes, so a killed process cannot leave the account locked forever.
 *
 * <p>It tries rather than waits. A second concurrent import fails immediately with a sentence
 * saying what is happening, which for a command line tool is better than appearing to hang.
 */
@Component
public class AccountLock {

    private final DataSource dataSource;

    public AccountLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Thrown when somebody else is already writing to this account. */
    public static class AccountBusyException extends RuntimeException {
        AccountBusyException(Account account) {
            super("another process is already writing to " + account.name()
                    + ". Only one writer per account is allowed, because two would both decide"
                    + " which lots a sale consumes from the same starting state and both be"
                    + " right about it. Wait for the other one to finish and try again.");
        }
    }

    /**
     * Runs the action while holding the account's lock.
     *
     * <p>The lock is released in a finally, and again by Postgres if this process dies holding
     * the connection. Both, because a lock that can be leaked is a lock that will be.
     */
    public <T> T whileHolding(Account account, Supplier<T> action) {
        long key = keyFor(account);
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, key)) {
                throw new AccountBusyException(account);
            }
            try {
                return action.get();
            } finally {
                unlock(connection, key);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "cannot take the write lock for " + account.name(), e);
        }
    }

    private static boolean tryLock(Connection connection, long key) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, long key) {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        } catch (SQLException e) {
            // Not rethrown: the work is done and committed, and closing the connection
            // releases the lock anyway. Failing here would turn a successful import into an
            // error about bookkeeping.
            org.slf4j.LoggerFactory.getLogger(AccountLock.class)
                    .warn("could not release the advisory lock, the connection close will: {}",
                            e.getMessage());
        }
    }

    /**
     * A stable 64 bit key for an account name.
     *
     * <p>FNV-1a rather than {@code String.hashCode}, which is 32 bits and would collide far
     * more often in a 64 bit keyspace. A collision is not a correctness problem: two unrelated
     * accounts would serialise against each other, which is slower and never wrong. Being
     * stable across JVMs matters more, because two processes have to agree on the key or the
     * lock protects nothing.
     */
    static long keyFor(Account account) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : account.name().getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
