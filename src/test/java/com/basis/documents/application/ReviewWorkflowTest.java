package com.basis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.documents.domain.ExtractionMethod;
import com.basis.documents.domain.FactStatus;
import com.basis.documents.domain.FactUnit;
import com.basis.documents.domain.NormalizedFact;
import com.basis.documents.domain.ReviewAction;
import com.basis.documents.domain.ReviewTask;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReviewWorkflowTest {
    private static NormalizedFact fact() {
        return new NormalizedFact("f", "ACME", "revenue", "consolidated", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                new BigDecimal("10"), "10", FactUnit.MONETARY, "USD", "doc", "sha", "page=1", ExtractionMethod.TEXT, 700, FactStatus.NEEDS_REVIEW, null);
    }
    @Test void approvalClosesTaskAndChangesFact() {
        var decision = new ReviewWorkflow().decide(new ReviewTask("t", "f", "LOW_CONFIDENCE", 0, true), fact(), 0, ReviewAction.APPROVE, "reviewer", "checked source", null);
        assertThat(decision.fact().status()).isEqualTo(FactStatus.APPROVED);
        assertThat(decision.task().open()).isFalse();
    }
    @Test void staleDecisionIsRejected() {
        assertThatThrownBy(() -> new ReviewWorkflow().decide(new ReviewTask("t", "f", "x", 1, true), fact(), 0, ReviewAction.REJECT, "r", "reason", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
    }
    @Test void correctionPreservesOldFactAndCreatesSuccessor() {
        var decision = new ReviewWorkflow().decide(new ReviewTask("t", "f", "x", 0, true), fact(), 0, ReviewAction.CORRECT, "r", "footnote", new BigDecimal("11"));
        assertThat(decision.fact().supersedesFactId()).isEqualTo("f");
        assertThat(decision.fact().value()).isEqualByComparingTo("11");
        assertThat(java.util.UUID.fromString(decision.fact().id())).isNotNull();
        var replay = new ReviewWorkflow().decide(new ReviewTask("t", "f", "x", 0, true), fact(), 0, ReviewAction.CORRECT, "r", "footnote", new BigDecimal("11"));
        assertThat(replay.fact().id()).isEqualTo(decision.fact().id());
    }

    @Test void taskCannotBeUsedWithAnotherFact() {
        assertThatThrownBy(() -> new ReviewWorkflow().decide(new ReviewTask("t", "other", "x", 0, true), fact(), 0,
                ReviewAction.APPROVE, "r", "reason", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
