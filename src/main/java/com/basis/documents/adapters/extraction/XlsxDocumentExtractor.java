package com.basis.documents.adapters.extraction;

import com.basis.documents.application.DocumentExtractor;
import com.basis.documents.domain.ExtractedField;
import com.basis.documents.domain.ExtractionMethod;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Reads visible XLSX cells as evidence-bearing table fields without evaluating formulas. */
@Component
public final class XlsxDocumentExtractor implements DocumentExtractor {
    @Override public boolean supports(String mediaType, String filename) {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equalsIgnoreCase(mediaType)
                || (filename != null && filename.toLowerCase().endsWith(".xlsx"));
    }

    @Override public List<ExtractedField> extract(InputStream input, String sourceVersion) {
        try (var workbook = new XSSFWorkbook(input)) {
            DataFormatter formatter = new DataFormatter();
            List<ExtractedField> fields = new ArrayList<>();
            for (var sheet : workbook) {
                for (Row row : sheet) {
                    for (var cell : row) {
                        String value = formatter.formatCellValue(cell).trim();
                        if (value.isEmpty()) continue;
                        String location = "sheet=" + sheet.getSheetName() + ",cell=" + cell.getAddress() + ",source=" + sourceVersion;
                        fields.add(new ExtractedField(UUID.randomUUID().toString(), "cell", value, null, null,
                                null, null, location, ExtractionMethod.XLSX, 850));
                    }
                }
            }
            return List.copyOf(fields);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("XLSX could not be read", e);
        }
    }
}
