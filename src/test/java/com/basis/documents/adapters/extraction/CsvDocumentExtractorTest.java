package com.basis.documents.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvDocumentExtractorTest {
    @Test void preservesQuotedCellsAndRoutesMissingPeriods() {
        var input = "Revenue,\"1,250\",2025-12-31\n\"Net, sales\",99\n";
        var fields = new CsvDocumentExtractor().extract(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), "sha");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).rawValue()).isEqualTo("1,250");
        assertThat(fields.get(0).confidencePermille()).isEqualTo(900);
        assertThat(fields.get(1).label()).isEqualTo("Net, sales");
        assertThat(fields.get(1).confidencePermille()).isEqualTo(600);
    }

    @Test void rejectsMalformedRows() {
        assertThatThrownBy(() -> new CsvDocumentExtractor().extract(new ByteArrayInputStream("broken\n".getBytes(StandardCharsets.UTF_8)), "sha"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("row 1");
    }
}
