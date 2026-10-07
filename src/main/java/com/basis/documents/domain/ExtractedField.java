package com.basis.documents.domain;

import java.time.LocalDate;
import java.util.Objects;

public record ExtractedField(String id, String label, String rawValue, String unit, String currency,
                             LocalDate periodStart, LocalDate periodEnd, String location,
                             ExtractionMethod method, int confidencePermille) {
    public ExtractedField {
        Objects.requireNonNull(id); Objects.requireNonNull(label); Objects.requireNonNull(rawValue);
        Objects.requireNonNull(location); Objects.requireNonNull(method);
        if (confidencePermille < 0 || confidencePermille > 1000) throw new IllegalArgumentException("confidence must be 0..1000");
    }
}
