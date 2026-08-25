package com.basis.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reads an uploaded file, and refuses it in a way that says what to do next.
 *
 * <p>A stranger's first upload is the one that fails. They have downloaded something from a
 * broker with a name they did not choose, in a format they have not looked at, and the only
 * thing standing between them and closing the tab is whether the error tells them something
 * they can act on. "Could not parse file" does not.
 *
 * <p>So every refusal here names the specific thing that was wrong and the specific next
 * step. The four that actually happen are: an empty or oversized file, a file that is not
 * CSV at all, a position statement uploaded into the transaction slot, and a valid statement
 * that simply has no transactions in it.
 */
@Component
// Web only. Scanned into a CLI context these would demand beans the web profile
// provides, which is how adding the web layer broke every Spring test at once.
@org.springframework.context.annotation.Profile("web")
public class UploadReader {

    private final WebConfig.Limits limits;

    public UploadReader(WebConfig.Limits limits) {
        this.limits = limits;
    }

    /** A refusal, phrased for somebody who has never seen this tool before. */
    public static class RejectedUpload extends RuntimeException {

        private final String nextStep;

        public RejectedUpload(String problem, String nextStep) {
            super(problem);
            this.nextStep = nextStep;
        }

        public String nextStep() {
            return nextStep;
        }
    }

    public record Read(List<String> lines, String filename, String headerLine) {
    }

    public Read read(MultipartFile file, String what) {
        if (file == null || file.isEmpty()) {
            throw new RejectedUpload(
                    "No " + what + " was uploaded.",
                    "Choose a CSV file exported from your broker, then try again.");
        }
        if (file.getSize() > limits.maxBytes()) {
            throw new RejectedUpload(
                    "That file is " + megabytes(file.getSize()) + ", which is larger than the "
                            + limits.maxDescription() + " limit.",
                    "Brokers cap each download at a date range, so a large file is usually several"
                            + " exports joined together. Upload them one at a time.");
        }

        List<String> lines = readLines(file);
        if (lines.isEmpty()) {
            throw new RejectedUpload(
                    "That file is empty.",
                    "Check the download actually completed, then upload it again.");
        }

        String header = headerOf(lines);
        if (header == null) {
            throw new RejectedUpload(
                    "Nothing in that file looks like a row of column headings.",
                    "basis reads CSV files, the kind a broker's download button produces. A PDF"
                            + " statement or an Excel workbook has to be exported as CSV first.");
        }
        return new Read(lines, safeName(file.getOriginalFilename()), header);
    }

    /**
     * Catches the swap: a holdings file uploaded where a transaction history belongs.
     *
     * <p>Worth its own message because it is the most likely mistake and the least obvious
     * one. Both files are CSVs of numbers about the same account, both come from the same
     * screen at some brokers, and a person who has just been asked for two files will
     * reasonably put the wrong one first. The generic answer would be "no broker recognised",
     * which sends them looking for a broker problem they do not have.
     */
    public void refuseIfItIsAPositionFile(String headerLine) {
        String header = headerLine.toLowerCase(Locale.ROOT);
        boolean looksLikeHoldings = header.contains("symbol") && header.contains("quantity");
        boolean hasTransactionColumns = header.contains("date")
                || header.contains("action")
                || header.contains("transaction")
                || header.contains("amount");
        if (looksLikeHoldings && !hasTransactionColumns) {
            throw new RejectedUpload(
                    "That looks like a list of what you currently hold, not a history of trades.",
                    "It belongs in the second box, the optional one. The first box wants a"
                            + " transaction history: the export with one row per buy, sell and"
                            + " dividend.");
        }
    }

    private List<String> readLines(MultipartFile file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() > limits.maxRows()) {
                    throw new RejectedUpload(
                            "That file has more than " + limits.maxRows() + " rows.",
                            "That is more history than this page will process at once. Upload it in"
                                    + " parts, or use the command line version, which has no limit.");
                }
            }
        } catch (IOException e) {
            // Deliberately not including the exception message: it can contain a temp file
            // path, and nothing about it helps the person reading the screen.
            throw new RejectedUpload(
                    "That file could not be read.",
                    "Try downloading it from your broker again.");
        }
        return lines;
    }

    /**
     * The first line with more than one comma separated cell.
     *
     * <p>Not simply the first line. A real Fidelity export opens with two blank lines before
     * the header and closes with paragraphs of legal disclaimer, and a reader that assumed
     * line one was the header would reject the genuine article.
     */
    private static String headerOf(List<String> lines) {
        for (String line : lines) {
            String candidate = line.startsWith("﻿") ? line.substring(1) : line;
            if (candidate.isBlank()) {
                continue;
            }
            if (candidate.split(",", -1).length >= 3) {
                return candidate;
            }
        }
        return null;
    }

    /** Filenames come from strangers, so only the last segment and only printable characters. */
    private static String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "upload.csv";
        }
        String base = original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._ -]", "");
        return base.isBlank() ? "upload.csv" : base.substring(0, Math.min(base.length(), 80));
    }

    /**
     * Exact, because the whole project bans binary floating point and a file size is no
     * exception. Dividing by 1024.0 here would have been the one double in the codebase, and
     * the ArchUnit rule caught it, which is the rule doing its job on the author of the rule.
     */
    private static String megabytes(long bytes) {
        return new java.math.BigDecimal(bytes)
                .divide(new java.math.BigDecimal(1024 * 1024), 1, java.math.RoundingMode.HALF_UP)
                .toPlainString() + " MB";
    }
}
