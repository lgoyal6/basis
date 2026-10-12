package com.basis.documents.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TesseractOcrDocumentExtractorTest {
    private static final String TESSERACT = "tesseract";
    private static final String PDFTOPPM = "pdftoppm";

    @Test
    void extractsImageTextWithPageAndLineProvenance() throws Exception {
        byte[] png = image("TOTAL REVENUE 1250");
        var extractor = new TesseractOcrDocumentExtractor(TESSERACT, PDFTOPPM);
        Assumptions.assumeTrue(extractor.supports("image/png", "scan.png"), "local OCR toolchain is unavailable");

        var fields = extractor.extract(new ByteArrayInputStream(png), "sha-image");

        assertThat(fields).isNotEmpty();
        assertThat(fields.getFirst().method()).isEqualTo(com.basis.documents.domain.ExtractionMethod.OCR);
        assertThat(fields.getFirst().location()).contains("page=1", "line=1", "sha-image");
        assertThat(fields).extracting(field -> field.rawValue().toUpperCase())
                .anyMatch(value -> value.contains("REVENUE"));
    }

    @Test
    void rasterizesScannedPdfBeforeOcr() throws Exception {
        byte[] png = image("NET INCOME 840");
        PathHolder pdf = scannedPdf(png);
        try {
            var extractor = new TesseractOcrDocumentExtractor(TESSERACT, PDFTOPPM);
            Assumptions.assumeTrue(extractor.supports("application/pdf", "scan.pdf"), "local OCR toolchain is unavailable");
            var fields = extractor.extract(Files.newInputStream(pdf.path), "sha-pdf");
            assertThat(fields).extracting(field -> field.rawValue().toUpperCase())
                    .anyMatch(value -> value.contains("INCOME"));
        } finally {
            Files.deleteIfExists(pdf.path);
        }
    }

    @Test
    void unavailableToolchainDoesNotAdvertiseOcrSupport() {
        var extractor = new TesseractOcrDocumentExtractor("/missing/tesseract", "/missing/pdftoppm");
        assertThat(extractor.supports("application/pdf", "scan.pdf")).isFalse();
        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(new byte[] {1}), "sha"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OCR is unavailable");
    }

    private static byte[] image(String text) throws Exception {
        BufferedImage image = new BufferedImage(1600, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(new Font("SansSerif", Font.BOLD, 86));
        graphics.drawString(text, 30, 150);
        graphics.dispose();
        var output = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static PathHolder scannedPdf(byte[] png) throws Exception {
        var path = Files.createTempFile("basis-scanned-", ".pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(612, 792));
            document.addPage(page);
            var image = LosslessFactory.createFromImage(document, ImageIO.read(new ByteArrayInputStream(png)));
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 30, 600, 540, 80);
            }
            document.save(path.toFile());
        }
        return new PathHolder(path);
    }

    private record PathHolder(java.nio.file.Path path) { }
}
