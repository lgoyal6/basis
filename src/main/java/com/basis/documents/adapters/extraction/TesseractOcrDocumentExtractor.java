package com.basis.documents.adapters.extraction;

import com.basis.documents.application.DocumentExtractor;
import com.basis.documents.domain.ExtractedField;
import com.basis.documents.domain.ExtractionMethod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;

/** Local, bounded OCR. Untrusted text never becomes verified financial evidence here. */
@Component
public final class TesseractOcrDocumentExtractor implements DocumentExtractor {
    static final int MAX_BYTES = 50 * 1024 * 1024;
    static final int MAX_PAGES = 20;
    private final String tesseract;
    private final String pdftoppm;
    private final Duration timeout;

    public TesseractOcrDocumentExtractor() {
        this(System.getenv().getOrDefault("BASIS_TESSERACT_BIN", "tesseract"),
                System.getenv().getOrDefault("BASIS_PDFTOPPM_BIN", "pdftoppm"));
    }

    TesseractOcrDocumentExtractor(String tesseract, String pdftoppm) {
        this(tesseract, pdftoppm, Duration.ofSeconds(30));
    }

    TesseractOcrDocumentExtractor(String tesseract, String pdftoppm, Duration timeout) {
        this.tesseract = tesseract;
        this.pdftoppm = pdftoppm;
        this.timeout = timeout;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("positive OCR timeout required");
    }

    @Override public boolean supports(String mediaType, String filename) {
        if (!available(tesseract, "--version")) return false;
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean image = List.of("image/png", "image/jpeg", "image/tiff").contains(mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT))
                || List.of(".png", ".jpg", ".jpeg", ".tif", ".tiff").stream().anyMatch(name::endsWith);
        boolean pdf = "application/pdf".equalsIgnoreCase(mediaType) || name.endsWith(".pdf");
        return image || (pdf && available(pdftoppm, "-v"));
    }

    @Override public List<ExtractedField> extract(InputStream input, String sourceVersion) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("OCR input must be 1..50 MiB");
            boolean pdf = bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
            int pages = 1;
            if (pdf) {
                try (var document = Loader.loadPDF(bytes)) { pages = document.getNumberOfPages(); }
                if (pages == 0 || pages > MAX_PAGES) throw new IllegalArgumentException("OCR PDF must contain 1..20 pages");
            }
            if (!available(tesseract, "--version") || (pdf && !available(pdftoppm, "-v"))) {
                throw new IllegalArgumentException("OCR is unavailable; configure Tesseract and pdftoppm");
            }
            Path directory = Files.createTempDirectory("basis-ocr-");
            try {
                Path source = directory.resolve(pdf ? "input.pdf" : "input.img");
                Files.write(source, bytes);
                List<ExtractedField> fields = new ArrayList<>();
                for (int page = 1; page <= pages; page++) {
                    Path image = source;
                    if (pdf) {
                        // One bounded raster at a time, rather than retaining all PDF images in memory.
                        Path prefix = directory.resolve("page");
                        run(List.of(pdftoppm, "-f", Integer.toString(page), "-l", Integer.toString(page),
                                "-scale-to", "2400", "-singlefile", "-png", source.toString(), prefix.toString()), directory, "PDF rasterization");
                        image = directory.resolve("page.png");
                    }
                    String text = run(List.of(tesseract, image.toString(), "stdout", "--psm", "6"), directory, "OCR");
                    int line = 0;
                    for (String raw : text.split("\\R")) {
                        line++;
                        String value = raw.trim();
                        if (!value.isEmpty()) fields.add(new ExtractedField(UUID.randomUUID().toString(), "text", value,
                                null, null, null, null, "page=" + page + ",line=" + line + ",source=" + sourceVersion,
                                ExtractionMethod.OCR, 400));
                    }
                    if (pdf) Files.deleteIfExists(image);
                }
                if (fields.isEmpty()) throw new IllegalArgumentException("OCR produced no text");
                return List.copyOf(fields);
            } finally {
                // Delete only this invocation's private files, including failed or partial rasters.
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) { throw new IllegalArgumentException("OCR could not read the document", e); }
    }

    private String run(List<String> command, Path directory, String operation) throws IOException {
        Path output = directory.resolve("stdout.txt"), error = directory.resolve("stderr.txt");
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException(operation + " timed out");
                if (Files.size(output) > 1024 * 1024 || Files.size(error) > 1024 * 1024) {
                    throw new IllegalArgumentException(operation + " output exceeds 1 MiB");
                }
            }
            if (Files.size(output) > 1024 * 1024 || Files.size(error) > 1024 * 1024) throw new IllegalArgumentException(operation + " output exceeds 1 MiB");
            // Provider diagnostics can contain filenames and document text. Keep them out of facts and user errors.
            if (process.exitValue() != 0) throw new IllegalArgumentException(operation + " failed with exit code " + process.exitValue());
            return Files.readString(output, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " was interrupted", e);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private boolean available(String command, String flag) {
        Process process = null;
        try {
            process = new ProcessBuilder(command, flag).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(2000, TimeUnit.MILLISECONDS) && process.exitValue() == 0;
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
