package com.basis.documents.adapters.persistence;

import com.basis.documents.application.IdempotencyStore;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Tenant-scoped idempotency claims backed by the V9 primary key. */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {
    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(String tenantId, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key is required");
        return jdbc.update("insert into idempotency_key (tenant_id, key) values (?, ?) on conflict (tenant_id, key) do nothing",
                uuid(tenantId), key) == 1;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("tenant identifiers must be UUIDs", e);
        }
    }
}
