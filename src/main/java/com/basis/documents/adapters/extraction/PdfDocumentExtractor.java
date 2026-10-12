package com.basis.documents.adapters.extraction;

import com.basis.documents.application.DocumentExtractor;
import com.basis.documents.domain.ExtractedField;
import com.basis.documents.domain.ExtractionMethod;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Extracts selectable PDF text while refusing to pretend an image-only page is text. */
@Component
public final class PdfDocumentExtractor implements DocumentExtractor {
    private final TesseractOcrDocumentExtractor ocr;
    public PdfDocumentExtractor() { this(new TesseractOcrDocumentExtractor()); }
    @Autowired public PdfDocumentExtractor(TesseractOcrDocumentExtractor ocr) { this.ocr = ocr; }
    @Override public boolean supports(String mediaType, String filename) {
        return "application/pdf".equalsIgnoreCase(mediaType)
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    @Override public List<ExtractedField> extract(InputStream input, String sourceVersion) {
        try {
            byte[] bytes = input.readNBytes(TesseractOcrDocumentExtractor.MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > TesseractOcrDocumentExtractor.MAX_BYTES) throw new IllegalArgumentException("PDF input must be 1..50 MiB");
            try (var document = Loader.loadPDF(bytes)) {
                List<ExtractedField> fields = new ArrayList<>();
                boolean hasScannedPage = false;
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    var stripper = new PDFTextStripper();
                    stripper.setStartPage(page); stripper.setEndPage(page);
                    String text = stripper.getText(document);
                    if (text.isBlank()) { hasScannedPage = true; continue; }
                    int line = 0;
                    for (String raw : text.split("\\R")) {
                        line++;
                        String value = raw.trim();
                        if (value.isEmpty()) continue;
                        fields.add(new ExtractedField(UUID.randomUUID().toString(), "text", value, null, null,
                                null, null, "page=" + page + ",line=" + line + ",source=" + sourceVersion,
                                ExtractionMethod.TEXT, 700));
                    }
                }
                // OCR all pages of a mixed PDF at the lower confidence, rather than silently
                // dropping its image-only pages or pretending the OCR lines were selectable text.
                if (hasScannedPage) return ocr.extract(new java.io.ByteArrayInputStream(bytes), sourceVersion);
                if (fields.isEmpty()) throw new IllegalArgumentException("PDF contains no text or pages");
                return List.copyOf(fields);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF could not be read", e);
        }
    }
}
