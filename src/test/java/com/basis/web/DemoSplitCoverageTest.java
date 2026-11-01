package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Commodity;
import com.basis.reconcile.CoverageStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DemoSplitCoverageTest {
    @Test
    void anUnlistedSecurityDoesNotBecomeAConfirmedEmptyHistory() {
        var coverage = DemoSplits.CALENDAR.coverageBetween(
                Commodity.equity("VTSAX"), LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        assertThat(coverage.status()).isEqualTo(CoverageStatus.NEVER_CHECKED);
        assertThat(coverage.isAuthoritative()).isFalse();
    }
}
