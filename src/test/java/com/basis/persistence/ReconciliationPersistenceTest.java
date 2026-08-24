package com.basis.persistence;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.buy;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Commodity;
import com.basis.ledger.Ledger;
import com.basis.reconcile.BreakRecord;
import com.basis.reconcile.BreakStatus;
import com.basis.reconcile.BrokerPositions;
import com.basis.reconcile.BrokerSnapshot;
import com.basis.reconcile.KnownSplit;
import com.basis.reconcile.ProbableCause;
import com.basis.reconcile.Reconciler;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * Breaks and reference data against a real Postgres.
 *
 * <p>The end to end test here is the product in one method: a history missing a split, a
 * broker statement that disagrees, reference data that knows why, and a break that says so
 * and can be settled by a person.
 */
@SpringBootTest
@Testcontainers
class ReconciliationPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);
    private static final LocalDate SPLIT_DATE = LocalDate.of(2026, 2, 20);

    @Autowired
    JdbcClient db;

    @Autowired
    BreakRecordRepository breaks;

    @Autowired
    ReferenceDataRepository referenceData;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM break_record").update();
        db.sql("DELETE FROM reference_data").update();
        db.sql("DELETE FROM reference_data_fetch").update();
        db.sql("DELETE FROM position").update();
    }

    @Test
    @DisplayName("a history missing a split produces a break that names the split and can be settled")
    void endToEnd() {
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "test");

        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        List<BreakRecord> found = new Reconciler(referenceData).reconcile(ledger.state(),
                BrokerSnapshot.ofSecurities(IBKR, AS_OF,
                        List.of(BrokerPositions.held(IBKR, AAPL, qty("40")))));

        assertThat(found).singleElement().satisfies(record -> {
            assertThat(record.cause().code()).isEqualTo(ProbableCause.UNAPPLIED_SPLIT);
            assertThat(record.cause().confident())
                    .as("the reference data corroborates the arithmetic")
                    .isTrue();
            assertThat(record.cause().explanation()).contains("4 for 1").contains("2026-02-20");
        });

        long id = breaks.record(found.get(0));
        assertThat(breaks.countOpen()).isEqualTo(1);

        List<BreakRecord> readBack = breaks.findOpen(IBKR);
        assertThat(readBack).singleElement().satisfies(record -> {
            assertThat(record.commodity()).isEqualTo(AAPL);
            assertThat(record.brokerQuantity()).isEqualTo(qty("40"));
            assertThat(record.computedQuantity()).isEqualTo(qty("10"));
            assertThat(record.cause().confident()).isTrue();
            assertThat(record.cause().suggestedAction()).contains("Apply the 4 for 1 split");
        });

        breaks.settle(id, BreakStatus.ACCEPTED, "applied the split and reimported");
        assertThat(breaks.countOpen()).isZero();
        assertThat(breaks.findOpen(IBKR)).isEmpty();
    }

    @Test
    @DisplayName("a cash break reads back as cash, not as a security called USD")
    void cashBreaksSurviveTheRoundTrip() {
        Ledger ledger = new Ledger();
        ledger.record(com.basis.support.Fixtures.openingCash(JAN_15, "o1", "1000.00"));

        List<BreakRecord> found = Reconciler.withoutReferenceData().reconcile(ledger.state(),
                BrokerSnapshot.complete(IBKR, AS_OF, List.of(BrokerPositions.cash(IBKR, usd("900.00")))));
        breaks.recordAll(found);

        assertThat(breaks.findOpen(IBKR)).singleElement().satisfies(record -> {
            assertThat(record.commodity()).isEqualTo(Commodity.of(USD));
            assertThat(record.commodity().isCash())
                    .as("stored rather than guessed back from the symbol")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("a settled break cannot be settled twice")
    void settlingIsOnce() {
        long id = breaks.record(aBreak());
        breaks.settle(id, BreakStatus.REJECTED, "the broker's statement was wrong");

        assertThatThrownBy(() -> breaks.settle(id, BreakStatus.ACCEPTED, "changed my mind"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not open");
    }

    @Test
    @DisplayName("settling into OPEN is refused, since that is not settling")
    void cannotSettleIntoOpen() {
        long id = breaks.record(aBreak());

        assertThatThrownBy(() -> breaks.settle(id, BreakStatus.OPEN, "no"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("causes can be counted, which is what makes them worth having codes for")
    void causesAreGroupable() {
        breaks.record(aBreak());
        breaks.record(aBreak());

        assertThat(breaks.causeSummary())
                .containsExactly("UNAPPLIED_SPLIT confident=false count=2");
    }

    @Test
    @DisplayName("reference data round trips through JSONB and reports its own staleness")
    void referenceDataRoundTrips() {
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "fmp");

        List<KnownSplit> splits = referenceData.coverageBetween(AAPL, JAN_15, AS_OF).splits();

        assertThat(splits).singleElement().satisfies(split -> {
            assertThat(split.numerator()).isEqualTo(4);
            assertThat(split.denominator()).isEqualTo(1);
            assertThat(split.date()).isEqualTo(SPLIT_DATE);
            assertThat(split.commodity()).isEqualTo(AAPL);
            assertThat(split.fetchedAt()).isAfter(Instant.EPOCH);
        });
        assertThat(referenceData.lastSuccessfulFetch("AAPL")).isNotNull();
    }

    @Test
    @DisplayName("refetching a split updates it rather than duplicating or failing")
    void cachingIsIdempotent() {
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "fmp");
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "fmp");

        assertThat(referenceData.countCached(ReferenceDataRepository.SPLIT)).isEqualTo(1);
    }

    @Test
    @DisplayName("a corrected ratio replaces the one that was cached")
    void refetchingCorrectsTheRatio() {
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 2, 1, "fmp");
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "fmp");

        assertThat(referenceData.coverageBetween(AAPL, JAN_15, AS_OF).splits())
                .singleElement()
                .satisfies(split -> assertThat(split.numerator()).isEqualTo(4));
    }

    @Test
    @DisplayName("splits outside the window are not returned")
    void windowIsRespected() {
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "fmp");

        assertThat(referenceData.coverageBetween(AAPL, SPLIT_DATE.plusDays(1), AS_OF).splits()).isEmpty();
        assertThat(referenceData.coverageBetween(AAPL, JAN_15, SPLIT_DATE.minusDays(1)).splits()).isEmpty();
        assertThat(referenceData.coverageBetween(AAPL, SPLIT_DATE, SPLIT_DATE).splits())
                .as("the range is closed at both ends")
                .hasSize(1);
        assertThat(referenceData.coverageBetween(AAPL, AS_OF, JAN_15).splits())
                .as("an inverted window asks for nothing rather than erroring")
                .isEmpty();
    }

    @Test
    @DisplayName("breaks survive a derived state rebuild, unlike positions and lots")
    void breaksAreNotDerivedState() {
        breaks.record(aBreak());

        db.sql("TRUNCATE TABLE position, lot, realized_gain RESTART IDENTITY").update();

        assertThat(breaks.countOpen())
                .as("a human's judgement is not recomputable from the posting table")
                .isEqualTo(1);
    }

    private static BreakRecord aBreak() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        return Reconciler.withoutReferenceData().reconcile(ledger.state(),
                BrokerSnapshot.ofSecurities(IBKR, AS_OF,
                        List.of(BrokerPositions.held(IBKR, AAPL, qty("40"))))).get(0);
    }
}
