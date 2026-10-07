package com.basis.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReconcilerTest {
    private static NormalizedFact fact(String id, String raw, BigDecimal value, FactUnit unit, String currency, int confidence) {
        return new NormalizedFact(id, "ACME", "revenue", "consolidated", LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31), value, raw, unit, currency, "filing.pdf", "sha256", "page=3", ExtractionMethod.TEXT,
                confidence, FactStatus.VERIFIED, null);
    }

    @Test void parenthesesAndScaleAreDeterministic() {
        assertThat(NumberNormalizer.parse("(1,250)", new BigDecimal("1000"))).isEqualByComparingTo("-1250000");
    }

    @Test void equivalentValuesRetainProvenanceInExplanation() {
        var result = Reconciler.compare(fact("a", "$10", new BigDecimal("10"), FactUnit.MONETARY, "USD", 1000),
                fact("b", "10.00", new BigDecimal("10.00"), FactUnit.MONETARY, "USD", 1000));
        assertThat(result.classification()).isEqualTo(ReconciliationClass.EQUIVALENT_AFTER_NORMALIZATION);
        assertThat(result.explanation()).contains("page=3", "left=$10", "right=10.00");
    }

    @Test void periodAndUnitMismatchesNeverAgree() {
        var quarter = fact("a", "10", new BigDecimal("10"), FactUnit.MONETARY, "USD", 1000);
        var other = new NormalizedFact("b", "ACME", "revenue", "consolidated", LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31), new BigDecimal("10"), "10", FactUnit.MONETARY, "EUR", "f", "h", "cell=B2",
                ExtractionMethod.XLSX, 1000, FactStatus.VERIFIED, null);
        assertThat(Reconciler.compare(quarter, other).classification()).isEqualTo(ReconciliationClass.UNIT_MISMATCH);
    }

    @Test void correctionsCreateNewFactAndOldValueCannotBeOverwritten() {
        var old = fact("a", "10", new BigDecimal("10"), FactUnit.MONETARY, "USD", 1000).withStatus(FactStatus.APPROVED);
        var corrected = NormalizedFact.corrected(old, "b", new BigDecimal("11"), "source footnote");
        assertThat(corrected.supersedesFactId()).isEqualTo("a");
        assertThat(corrected.status()).isEqualTo(FactStatus.APPROVED);
        assertThatThrownBy(() -> old.withStatus(FactStatus.VERIFIED)).isInstanceOf(IllegalStateException.class);
    }
}
