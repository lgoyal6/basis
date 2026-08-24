package com.basis.reference;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.qty;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.event.Buy;
import com.basis.ledger.Ledger;
import com.basis.persistence.BreakRecordRepository;
import com.basis.persistence.ReferenceDataRepository;
import com.basis.reconcile.BreakRecord;
import com.basis.reconcile.BrokerPositions;
import com.basis.reconcile.BrokerSnapshot;
import com.basis.reconcile.ProbableCause;
import com.basis.reconcile.Reconciler;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole thing, for real: fetch Apple's split history from the live provider, put it in
 * Postgres, and watch a break go from a suspicion to a finding that names the date.
 *
 * <p>Uses Apple's actual 2020 split rather than a fixture, so this is the test that would
 * notice if the provider, the ingest SQL, the coverage model and the reconciler ever stopped
 * agreeing with each other. Off by default: run with {@code -PwithNetwork}.
 */
@Tag("network")
@EnabledIfEnvironmentVariable(named = "FMP_KEY", matches = ".+")
@SpringBootTest(properties = {"basis.fmp.enabled=true", "basis.fmp.refresh-after=0s"})
@Testcontainers
class FmpLiveRefreshTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** The day before Apple's 4 for 1, so the purchase predates the split it never applied. */
    private static final LocalDate BOUGHT_ON = LocalDate.of(2020, 8, 28);
    private static final LocalDate AS_OF = LocalDate.of(2020, 9, 30);

    @Autowired
    JdbcClient db;

    @Autowired
    SplitRefreshService refresh;

    @Autowired
    ReferenceDataRepository referenceData;

    @Autowired
    BreakRecordRepository breaks;

    @Test
    @DisplayName("a real split history turns a real ratio into a break that names the date")
    void liveRefreshExplainsARealBreak() {
        db.sql("DELETE FROM reference_data").update();
        db.sql("DELETE FROM reference_data_fetch").update();
        db.sql("DELETE FROM break_record").update();

        // A history that stops before the split: 10 shares bought, and nothing since.
        Ledger ledger = new Ledger();
        ledger.record(new Buy(BOUGHT_ON, IBKR, "b1", "{\"ref\":\"b1\"}", AAPL,
                Quantity.of("10"), Price.of("499.23", com.basis.support.Fixtures.USD),
                com.basis.support.Fixtures.usd("0.00")));

        // The broker, which did apply the split, reports 40.
        BrokerSnapshot statement = BrokerSnapshot.ofSecurities(IBKR, AS_OF,
                List.of(BrokerPositions.held(IBKR, AAPL, qty("40"))));

        BreakRecord beforeFetch = Reconciler.withoutReferenceData()
                .reconcile(ledger.state(), statement).get(0);
        assertThat(beforeFetch.cause().confident())
                .as("nothing has been fetched yet, so the ratio is only a suspicion")
                .isFalse();
        assertThat(beforeFetch.cause().explanation()).contains("never been fetched");

        SplitRefreshService.RefreshReport report = refresh.refresh(List.of("AAPL"));
        assertThat(report.succeeded()).as("live provider said: %s", report).isEqualTo(1);
        assertThat(report.splitsWritten()).isPositive();

        BreakRecord afterFetch = new Reconciler(referenceData).reconcile(ledger.state(), statement).get(0);

        assertThat(afterFetch.cause().confident())
                .as("the real split history corroborates it")
                .isTrue();
        assertThat(afterFetch.cause().code()).isEqualTo(ProbableCause.UNAPPLIED_SPLIT);
        assertThat(afterFetch.cause().explanation())
                .contains("4 for 1")
                .contains("2020-08-31");
        assertThat(afterFetch.cause().suggestedAction()).contains("Apply the 4 for 1 split");

        breaks.record(afterFetch);
        assertThat(breaks.causeSummary()).containsExactly("UNAPPLIED_SPLIT confident=true count=1");
    }
}
