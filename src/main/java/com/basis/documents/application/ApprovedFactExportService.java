/* Exports approved facts with their exact persisted review decision.
 * Uses JdbcReviewRepository results and DocumentIngestService current-source state.
 * One SQL snapshot excludes unreviewed, stale and cross-tenant rows.
 */
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
            out.println("fact_id,issuer,fact_type,context,value,unit,currency,period_start,period_end,source_document,source_version,location,extraction_method,confidence,review_decision_id");
            // One statement gives an MVCC snapshot of facts, source freshness and decisions.
            jdbc.query("select f.*, decision.id as review_decision_id from normalized_fact f "
                    + "join lateral (select r.id from review_decision r where r.tenant_id=f.tenant_id "
                    + "and r.result_fact_id=f.id and r.action in ('APPROVE','CORRECT') "
                    + "and r.new_status='APPROVED' order by r.created_at desc, r.id desc limit 1) decision on true "
                    + "where f.tenant_id=? and f.status='APPROVED' "
                    + "and not exists (select 1 from document d left join document_version v on v.id=d.current_version_id and v.document_id=d.id and v.tenant_id=d.tenant_id "
                    + "where d.tenant_id=f.tenant_id and d.document_key=f.source_document "
                    + "and v.sha256 is distinct from f.source_version) order by f.period_end, f.id",
                    (RowCallbackHandler) rs -> out.println(String.join(",", csv(rs.getString("id")), csv(rs.getString("issuer")),
                            csv(rs.getString("fact_type")), csv(rs.getString("context")), csv(rs.getString("value")),
                            csv(rs.getString("unit")), csv(rs.getString("currency")), csv(rs.getString("period_start")),
                            csv(rs.getString("period_end")), csv(rs.getString("source_document")), csv(rs.getString("source_version")),
                            csv(rs.getString("location")), csv(rs.getString("extraction_method")), csv(rs.getString("confidence")),
                            csv(rs.getString("review_decision_id")))), tenant);
        }
        return bytes.toByteArray();
    }

    private static String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
