/* Checks local OCR provenance, conservative candidates and process failures.
 * Exercises TesseractOcrDocumentExtractor with real images and small tool controls.
 * Malformed values stay raw; unavailable or stalled tools cannot pass as extraction.
 */
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
    void unavailableToolchainDoesNotAdvertiseOcrSupport() throws Exception {
        var extractor = new TesseractOcrDocumentExtractor("/missing/tesseract", "/missing/pdftoppm");
        assertThat(extractor.supports("application/pdf", "scan.pdf")).isFalse();
        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(smallImage("png")), "sha"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OCR is unavailable");
    }

    @Test void diagnosticsNeverBecomeExtractedText() throws Exception {
        Path tool = tool("echo 'TOTAL 1250'; echo 'PRIVATE diagnostic' >&2");
        try {
            var extractor = new TesseractOcrDocumentExtractor(tool.toString(), "/missing/pdf");
            var fields = extractor.extract(new ByteArrayInputStream(smallImage("png")), "sha");
            assertThat(fields).extracting(field -> field.rawValue()).containsExactly("TOTAL 1250");
        } finally { Files.delete(tool); }
    }

    @Test void hungProviderTimesOutInsteadOfHoldingWorkerForever() throws Exception {
        Path tool = tool("sleep 30");
        Path pdfTool = tool("if [ \"$1\" = \"-v\" ]; then exit 0; fi; exit 1");
        try {
            var extractor = new TesseractOcrDocumentExtractor(tool.toString(), pdfTool.toString(), java.time.Duration.ofMillis(200));
            assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(smallImage("png")), "sha"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
        } finally { Files.delete(tool); Files.delete(pdfTool); }
    }

    private static Path tool(String body) throws Exception {
        Path tool = Files.createTempFile("basis-test-ocr-", ".sh");
        Files.writeString(tool, "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then exit 0; fi\n" + body + "\n");
        assertThat(tool.toFile().setExecutable(true)).isTrue();
        return tool;
    }

    @Test void compressedImagesCannotBypassDecodedDimensionAndPixelLimits() throws Exception {
        Path tool = recordingTool();
        Path invoked = Path.of(tool + ".invoked");
        try {
            var extractor = new TesseractOcrDocumentExtractor(tool.toString(), "/missing/pdf");
            for (int[] dimensions : java.util.List.of(new int[] {10_001, 1}, new int[] {5_000, 4_001})) {
                byte[] compressed = compressedPng(dimensions[0], dimensions[1]);
                assertThat(compressed).hasSizeLessThan(100_000);
                assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(compressed), "oversized"))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image dimensions");
                assertThat(invoked).doesNotExist();
            }
        } finally { Files.deleteIfExists(invoked); Files.delete(tool); }
    }

    @Test void validImageHeadersAtTheLimitsReachOcr() throws Exception {
        Path tool = tool("echo 'TOTAL 1250'");
        try {
            var extractor = new TesseractOcrDocumentExtractor(tool.toString(), "/missing/pdf");
            for (byte[] image : java.util.List.of(compressedPng(10_000, 1), compressedPng(5_000, 4_000),
                    smallImage("jpeg"), smallTiff(20))) {
                assertThat(extractor.extract(new ByteArrayInputStream(image), "valid"))
                        .extracting(field -> field.rawValue()).containsExactly("TOTAL 1250");
            }
        } finally { Files.delete(tool); }
    }

    @Test void corruptOrExcessiveFrameImagesNeverReachOcr() throws Exception {
        Path tool = recordingTool();
        Path invoked = Path.of(tool + ".invoked");
        try {
            var extractor = new TesseractOcrDocumentExtractor(tool.toString(), "/missing/pdf");
            for (byte[] bytes : java.util.List.of(new byte[] {1}, java.util.Arrays.copyOf(smallImage("png"), 24), smallTiff(21))) {
                assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(bytes), "invalid"))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(invoked).doesNotExist();
            }
        } finally { Files.deleteIfExists(invoked); Files.delete(tool); }
    }

    private static Path recordingTool() throws Exception {
        Path tool = tool("echo 'TOTAL 1250'");
        Files.writeString(tool, Files.readString(tool).replace("#!/bin/sh\n", "#!/bin/sh\ntouch \"$0.invoked\"\n"));
        return tool;
    }

    private static byte[] smallImage(String format) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out)).isTrue();
        return out.toByteArray();
    }

    private static byte[] smallTiff(int frames) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (var out = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out); writer.prepareWriteSequence(null);
            var image = new javax.imageio.IIOImage(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), null, null);
            for (int i = 0; i < frames; i++) writer.writeToSequence(image, null);
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    // A valid grayscale PNG with a tiny compressed payload, built one row at a time.
    // Generating the fixture never allocates its decoded raster or invokes a decoder.
    private static byte[] compressedPng(int width, int height) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var png = new java.io.DataOutputStream(bytes);
        png.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        var header = new java.io.ByteArrayOutputStream();
        var data = new java.io.DataOutputStream(header);
        data.writeInt(width); data.writeInt(height);
        data.write(new byte[] {8, 0, 0, 0, 0});
        pngChunk(png, "IHDR", header.toByteArray());
        var compressed = new java.io.ByteArrayOutputStream();
        try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
            byte[] row = new byte[width + 1];
            for (int i = 0; i < height; i++) deflater.write(row);
        }
        pngChunk(png, "IDAT", compressed.toByteArray());
        pngChunk(png, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void pngChunk(java.io.DataOutputStream png, String type, byte[] content) throws Exception {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var crc = new java.util.zip.CRC32(); crc.update(name); crc.update(content);
        png.writeInt(content.length); png.write(name); png.write(content); png.writeInt((int) crc.getValue());
    }

    @Test void explicitNumericCandidatesRetainRawLinesAndRejectMissingOrInvalidDates() throws Exception {
        Path tool = tool("printf 'REVENUE: 1,250.50 USD 2025-12-31\\nCOST: 30 EUR 2025-02-30\\nCASH: 50\\nASSETS: 100 SHARES 2024-12-31\\n'");
        try {
            var fields = new TesseractOcrDocumentExtractor(tool.toString(), "/missing/pdf")
                    .extract(new ByteArrayInputStream(smallImage("png")), "version");
            assertThat(fields.stream().filter(f -> f.label().equals("text"))).hasSize(4);
            var numeric = fields.stream().filter(f -> f.periodEnd() != null).toList();
            assertThat(numeric).hasSize(2);
            assertThat(numeric.getFirst().rawValue()).isEqualTo("1,250.50");
            assertThat(numeric.getFirst().currency()).isEqualTo("USD");
            assertThat(numeric.getFirst().confidencePermille()).isEqualTo(400);
            assertThat(numeric.getFirst().location()).contains("page=1,line=1,source=version");
            assertThat(numeric.getLast().unit()).isEqualTo("SHARES");
            assertThat(numeric.getLast().currency()).isNull();
        } finally { Files.delete(tool); }
    }

    @Test void malformedDigitGroupingStaysRawEvidence() throws Exception {
        Path tool = tool("printf 'REVENUE: 1,,250 USD 2025-12-31\\nCASH: 1,25 USD 2025-12-31\\n'");
        try {
            var fields = new TesseractOcrDocumentExtractor(tool.toString(), "/missing/pdf")
                    .extract(new ByteArrayInputStream(smallImage("png")), "version");
            assertThat(fields).hasSize(2);
            assertThat(fields).allMatch(field -> field.label().equals("text") && field.periodEnd() == null);
        } finally { Files.delete(tool); }
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
