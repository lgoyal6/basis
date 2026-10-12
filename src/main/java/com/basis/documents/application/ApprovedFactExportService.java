package com.basis.documents.application;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

/** Exports only reviewer-approved facts, retaining enough provenance to audit every row. */
@Service
public final class ApprovedFactExportService {
    private final JdbcTemplate jdbc;

    public ApprovedFactExportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public byte[] export(String tenantId) {
        UUID tenant;
        try { tenant = UUID.fromString(tenantId); }
        catch (RuntimeException e) { throw new IllegalArgumentException("tenant id is invalid", e); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8))) {
            out.println("fact_id,issuer,fact_type,context,value,unit,currency,period_start,period_end,source_document,source_version,location,extraction_method,confidence");
            jdbc.query("select id, issuer, fact_type, context, value, unit, currency, period_start, period_end, source_document, source_version, location, extraction_method, confidence "
                    + "from normalized_fact where tenant_id=? and status='APPROVED' order by period_end, id",
                    (RowCallbackHandler) rs -> out.println(String.join(",", csv(rs.getString("id")), csv(rs.getString("issuer")),
                            csv(rs.getString("fact_type")), csv(rs.getString("context")), csv(rs.getString("value")),
                            csv(rs.getString("unit")), csv(rs.getString("currency")), csv(rs.getString("period_start")),
                            csv(rs.getString("period_end")), csv(rs.getString("source_document")), csv(rs.getString("source_version")),
                            csv(rs.getString("location")), csv(rs.getString("extraction_method")), csv(rs.getString("confidence")))), tenant);
        }
        return bytes.toByteArray();
    }

    private static String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
