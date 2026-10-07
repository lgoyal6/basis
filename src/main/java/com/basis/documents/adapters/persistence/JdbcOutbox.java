package com.basis.documents.adapters.persistence;

import com.basis.documents.application.Outbox;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only event adapter. Publishing is deliberately separate from the transaction that appends. */
@Repository
public class JdbcOutbox implements Outbox {
    private final JdbcTemplate jdbc;

    public JdbcOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String tenantId, String type, String payload) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type is required");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("event payload is required");
        jdbc.update("insert into outbox_event (id, tenant_id, event_type, payload) values (?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), uuid(tenantId), type, payload);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("tenant identifiers must be UUIDs", e);
        }
    }
}
