package com.basis.persistence;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Price;
import com.basis.domain.event.Buy;
import com.basis.domain.event.OpeningBalance;
import com.basis.domain.event.Sell;
import com.basis.importer.ImportService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two writers, one account, and the over disposal that used to be possible.
 *
 * <p>Recording an event hydrates the whole ledger from the posting table, decides which lots a
 * sale consumes from that snapshot, and appends the result. Nothing about that is protected by a
 * constraint: lot quantities are derived state, not a column with a check on it. So two
 * processes selling the same shares at the same time both read the same open lots, both pick
 * them, and both succeed. The position is left over disposed and every later gain is computed
 * from a basis that was counted twice, with no error anywhere.
 *
 * <p>This is the test that made the lock worth writing rather than assuming.
 */
@SpringBootTest
@Testcontainers
class ConcurrentWriterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ImportService importer;

    @Autowired
    private AccountLock accountLock;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("two concurrent sells of the same shares cannot both succeed")
    void concurrentSellsCannotBothConsumeTheSameLots() throws Exception {
        givenTenSharesHeld("race");

        // Both threads try to sell all ten. Exactly one is entitled to.
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        try {
            List<Future<?>> attempts = List.of(
                    pool.submit(() -> attemptSell("race", "a", bothReady, succeeded, refused)),
                    pool.submit(() -> attemptSell("race", "b", bothReady, succeeded, refused)));
            for (Future<?> attempt : attempts) {
                attempt.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get() + refused.get()).as("both threads finished").isEqualTo(2);
        assertThat(succeeded.get())
                .as("one sale is legitimate and one is not, whichever arrives first")
                .isEqualTo(1);

        // The real assertion. Regardless of which thread won, the shares disposed of must
        // never exceed the shares that were held.
        java.math.BigDecimal sold = db.sql("""
                SELECT coalesce(sum(quantity), 0) FROM posting
                 WHERE commodity = 'AAPL' AND quantity < 0
                """).query(java.math.BigDecimal.class).single().abs();

        assertThat(sold)
                .as("ten shares were held, so at most ten can have been sold")
                .isLessThanOrEqualTo(new java.math.BigDecimal("10"));
    }

    @Test
    @DisplayName("the second writer is told what is happening rather than left to hang")
    void theRefusalSaysWhy() {
        Account account = Account.of("Assets:Broker:Busy");

        assertThatThrownBy(() -> accountLock.whileHolding(account,
                        () -> accountLock.whileHolding(account, () -> "never reached")))
                .isInstanceOf(AccountLock.AccountBusyException.class)
                .hasMessageContaining("already writing to Assets:Broker:Busy")
                .as("a command line tool that appears to hang is worse than one that explains")
                .hasMessageContaining("try again");
    }

    @Test
    @DisplayName("the lock is released when the work finishes, so the next writer gets in")
    void theLockIsNotLeaked() {
        Account account = Account.of("Assets:Broker:Sequential");

        assertThat(accountLock.whileHolding(account, () -> "first")).isEqualTo("first");
        assertThat(accountLock.whileHolding(account, () -> "second"))
                .as("a lock that survived the first call would block this")
                .isEqualTo("second");
    }

    @Test
    @DisplayName("a failure inside the work still releases the lock")
    void aFailureDoesNotLeakTheLock() {
        Account account = Account.of("Assets:Broker:Failing");

        assertThatThrownBy(() -> accountLock.whileHolding(account, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(accountLock.whileHolding(account, () -> "after"))
                .as("otherwise one failed import would lock the account until a restart")
                .isEqualTo("after");
    }

    @Test
    @DisplayName("different accounts do not block each other")
    void separateAccountsRunInParallel() throws Exception {
        CountDownLatch insideFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            pool.submit(() -> accountLock.whileHolding(Account.of("Assets:Broker:One"), () -> {
                insideFirst.countDown();
                try {
                    // Held until the other account has been through, which can only happen if
                    // the locks are genuinely per account.
                    secondDone.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "one";
            }));

            assertThat(insideFirst.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(accountLock.whileHolding(Account.of("Assets:Broker:Two"), () -> "two"))
                    .as("a global lock would deadlock here instead")
                    .isEqualTo("two");
            secondDone.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the lock key is stable, because two processes have to agree on it")
    void theKeyIsStableAcrossRuns() {
        Account account = Account.of("Assets:Broker:IBKR");

        assertThat(AccountLock.keyFor(account))
                .as("String.hashCode would be 32 bits and is not guaranteed stable; this is")
                .isEqualTo(AccountLock.keyFor(Account.of("Assets:Broker:IBKR")));
        assertThat(AccountLock.keyFor(account))
                .isNotEqualTo(AccountLock.keyFor(Account.of("Assets:Broker:Schwab")));
    }

    private void attemptSell(String suffix, String ref, CountDownLatch ready,
            AtomicInteger succeeded, AtomicInteger refused) {
        try {
            ready.countDown();
            ready.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            importer.recordAsserted(new Sell(JAN_15.plusDays(5), IBKR, "sell-" + ref,
                    "{\"ref\":\"" + ref + "\"}", AAPL, qty("10"), Price.of("120.00", USD),
                    usd("0.00"), LotSelectionMethod.FIFO, List.of()), Path.of("sell-" + ref));
            succeeded.incrementAndGet();
        } catch (RuntimeException expected) {
            // Either the lock refused it or the ledger refused it for want of lots. Both are
            // correct outcomes; what matters is that they did not both write.
            refused.incrementAndGet();
        }
    }

    private void givenTenSharesHeld(String ref) {
        importer.recordAsserted(new OpeningBalance(JAN_15, IBKR, "cash-" + ref, "{}",
                Commodity.of(USD), qty("100000"), null), Path.of("open-cash"));
        importer.recordAsserted(new Buy(JAN_15.plusDays(1), IBKR, "buy-" + ref, "{}", AAPL,
                qty("10"), Price.of("100.00", USD), usd("0.00")), Path.of("buy"));
    }
}
