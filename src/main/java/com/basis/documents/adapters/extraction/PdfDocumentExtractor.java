package com.basis.documents.adapters.extraction;

import com.basis.documents.application.DocumentExtractor;
import com.basis.documents.domain.ExtractedField;
import com.basis.documents.domain.ExtractionMethod;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Extracts selectable PDF text while refusing to pretend an image-only page is text. */
@Component
public final class PdfDocumentExtractor implements DocumentExtractor {
    @Override public boolean supports(String mediaType, String filename) {
        return "application/pdf".equalsIgnoreCase(mediaType)
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    @Override public List<ExtractedField> extract(InputStream input, String sourceVersion) {
        try {
            byte[] bytes = input.readAllBytes();
            try (var document = Loader.loadPDF(bytes)) {
                String text = new PDFTextStripper().getText(document);
                if (text.isBlank()) {
                    throw new IllegalArgumentException("PDF has no selectable text; configure an OCR provider before processing scanned pages");
                }
                List<ExtractedField> fields = new ArrayList<>();
                int line = 0;
                for (String raw : text.split("\\R")) {
                    line++;
                    String value = raw.trim();
                    if (value.isEmpty()) continue;
                    fields.add(new ExtractedField(UUID.randomUUID().toString(), "text", value, null, null,
                            null, null, "page-text-line=" + line + ",source=" + sourceVersion,
                            ExtractionMethod.TEXT, 700));
                }
                return List.copyOf(fields);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF could not be read", e);
        }
    }
}
