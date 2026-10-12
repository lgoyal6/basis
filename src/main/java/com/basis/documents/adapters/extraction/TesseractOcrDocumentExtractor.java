package com.basis.documents.adapters.extraction;

import com.basis.documents.application.DocumentExtractor;
import com.basis.documents.domain.ExtractedField;
import com.basis.documents.domain.ExtractionMethod;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Optional OCR adapter for image uploads and scanned PDFs.
 *
 * <p>The binaries are invoked with argument arrays, never through a shell. A missing local
 * toolchain makes this adapter unavailable, so a deployment cannot accept a document it has
 * no way to process. OCR output remains lower-confidence text with explicit page and line
 * provenance; it is not treated as a verified financial fact by this adapter.
 */
@Component
public final class TesseractOcrDocumentExtractor implements DocumentExtractor {
    private final String tesseract;
    private final String pdftoppm;

    public TesseractOcrDocumentExtractor() {
        this(System.getenv().getOrDefault("BASIS_TESSERACT_BIN", "tesseract"),
                System.getenv().getOrDefault("BASIS_PDFTOPPM_BIN", "pdftoppm"));
    }

    TesseractOcrDocumentExtractor(String tesseract, String pdftoppm) {
        this.tesseract = tesseract;
        this.pdftoppm = pdftoppm;
    }

    @Override
    public boolean supports(String mediaType, String filename) {
        if (!toolAvailable(tesseract)) return false;
        boolean image = mediaType != null && (mediaType.equalsIgnoreCase("image/png")
                || mediaType.equalsIgnoreCase("image/jpeg")
                || mediaType.equalsIgnoreCase("image/tiff"));
        boolean imageName = filename != null && List.of(".png", ".jpg", ".jpeg", ".tif", ".tiff")
                .stream().anyMatch(ext -> filename.toLowerCase(Locale.ROOT).endsWith(ext));
        boolean pdf = "application/pdf".equalsIgnoreCase(mediaType)
                || (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf"));
        return (image || imageName) || (pdf && toolAvailable(pdftoppm));
    }

    @Override
    public List<ExtractedField> extract(InputStream input, String sourceVersion) {
        if (!supports("application/pdf", "document.pdf") && !toolAvailable(tesseract)) {
            throw new IllegalArgumentException("OCR is unavailable; configure Tesseract and pdftoppm");
        }
        try {
            // Keep a PDF suffix so pdftoppm can select its PDF input path. It still detects
            // image bytes by magic when the rasterization probe falls back to Tesseract.
            Path inputPath = Files.createTempFile("basis-ocr-", ".pdf");
            Files.write(inputPath, input.readAllBytes());
            try {
                List<Path> pages = rasterizeIfPdf(inputPath);
                try {
                    List<ExtractedField> fields = new ArrayList<>();
                    for (int page = 0; page < pages.size(); page++) {
                        String text = runTesseract(pages.get(page));
                        int line = 0;
                        for (String raw : text.split("\\R")) {
                            String value = raw.trim();
                            if (value.isEmpty()) continue;
                            line++;
                            fields.add(new ExtractedField(UUID.randomUUID().toString(), "text", value,
                                    null, null, null, null,
                                    "page=" + (page + 1) + ",line=" + line + ",source=" + sourceVersion,
                                    ExtractionMethod.OCR, 400));
                        }
                    }
                    if (fields.isEmpty()) throw new IllegalArgumentException("OCR produced no text");
                    return List.copyOf(fields);
                } finally {
                    cleanupRasterizedPages(inputPath, pages);
                }
            } finally {
                Files.deleteIfExists(inputPath);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("OCR could not read the document", e);
        }
    }

    private List<Path> rasterizeIfPdf(Path input) throws IOException {
        if (!toolAvailable(pdftoppm)) return List.of(input);
        Path directory = Files.createTempDirectory("basis-ocr-pages-");
        Path prefix = directory.resolve("page");
        try {
            run(List.of(pdftoppm, "-r", "300", "-png", input.toString(), prefix.toString()), "PDF rasterization");
        } catch (IllegalArgumentException notPdf) {
            // Images are valid OCR inputs too. pdftoppm is used as a capability probe because
            // the extractor interface receives bytes, not the upload media type.
            Files.deleteIfExists(directory);
            return List.of(input);
        }
        List<Path> pages;
        try (var stream = Files.list(directory)) {
            pages = stream.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (pages.isEmpty()) throw new IllegalArgumentException("scanned PDF produced no pages");
        return pages;
    }

    private static void cleanupRasterizedPages(Path input, List<Path> pages) throws IOException {
        if (pages.size() == 1 && pages.getFirst().equals(input)) return;
        Path directory = pages.getFirst().getParent();
        for (Path page : pages) Files.deleteIfExists(page);
        Files.deleteIfExists(directory);
    }

    private String runTesseract(Path image) throws IOException {
        return new String(run(List.of(tesseract, image.toString(), "stdout", "--psm", "6"), "OCR"),
                StandardCharsets.UTF_8);
    }

    private byte[] run(List<String> command, String operation) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output;
        try {
            try (var stream = process.getInputStream()) {
                output = stream.readAllBytes();
            }
            int exit = process.waitFor();
            if (exit != 0) throw new IllegalArgumentException(operation + " failed: "
                    + new String(output, StandardCharsets.UTF_8).trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException(operation + " was interrupted", e);
        } finally {
            process.destroy();
        }
        return output;
    }

    private static boolean toolAvailable(String command) {
        try {
            for (String probe : List.of("--version", "--help")) {
                Process process = new ProcessBuilder(command, probe).redirectErrorStream(true).start();
                try {
                    if (process.waitFor() == 0) return true;
                } finally {
                    process.destroy();
                }
            }
            return false;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
