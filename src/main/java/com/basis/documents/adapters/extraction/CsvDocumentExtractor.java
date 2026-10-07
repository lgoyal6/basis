package com.basis.documents.adapters.extraction;

import com.basis.documents.application.DocumentExtractor;
import com.basis.documents.domain.ExtractedField;
import com.basis.documents.domain.ExtractionMethod;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Conservative CSV adapter: preserves the source row and routes ambiguous rows for review. */
public final class CsvDocumentExtractor implements DocumentExtractor {
    @Override public boolean supports(String mediaType, String filename) {
        return "text/csv".equalsIgnoreCase(mediaType) || (filename != null && filename.toLowerCase().endsWith(".csv"));
    }
    @Override public List<ExtractedField> extract(InputStream input, String sourceVersion) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            List<ExtractedField> fields = new ArrayList<>(); String line; int row = 0;
            while ((line = reader.readLine()) != null) {
                row++; if (line.isBlank()) continue;
                List<String> cells = split(line); if (cells.size() < 2) throw new IllegalArgumentException("CSV row " + row + " needs label and value");
                String label = cells.get(0).trim(); String raw = cells.get(1).trim();
                LocalDate period = cells.size() > 2 && !cells.get(2).isBlank() ? LocalDate.parse(cells.get(2).trim()) : null;
                fields.add(new ExtractedField(UUID.randomUUID().toString(), label, raw, null, null, null, period,
                        "row=" + row + ",source=" + sourceVersion, ExtractionMethod.CSV, period == null ? 600 : 900));
            }
            return List.copyOf(fields);
        } catch (IOException e) { throw new IllegalArgumentException("CSV could not be read", e); }
    }
    private static List<String> split(String line) {
        List<String> out = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) { char c = line.charAt(i);
            if (c == '"') { if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = !quoted; }
            else if (c == ',' && !quoted) { out.add(cell.toString()); cell.setLength(0); } else cell.append(c);
        }
        if (quoted) throw new IllegalArgumentException("CSV has an unterminated quoted field");
        out.add(cell.toString()); return out;
    }
}
