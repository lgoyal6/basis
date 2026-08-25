package com.basis.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a load balancer asks before sending traffic.
 *
 * <p>Reaches the database rather than returning a constant. An instance whose process is up
 * but whose connection pool is exhausted or whose Postgres has gone is not healthy, and a
 * health check that cannot tell the difference is a health check that keeps a broken instance
 * in rotation.
 *
 * <p>Flyway has already run by the time this can answer at all: Spring applies migrations
 * during context startup, and a failed migration fails the context, so the port never opens.
 * That is the ordering the deploy depends on, and it comes for free rather than needing a
 * lock of its own. Flyway takes its own lock on the schema history table, which is what stops
 * two instances migrating at once.
 */
@RestController
@org.springframework.context.annotation.Profile("web")
public class HealthController {

    private final JdbcClient db;
    private final SessionStore sessions;

    public HealthController(JdbcClient db, SessionStore sessions) {
        this.db = db;
        this.sessions = sessions;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        try {
            db.sql("SELECT 1").query(Integer.class).single();
        } catch (RuntimeException failure) {
            // The reason is deliberately not returned. A health endpoint is unauthenticated,
            // and a database error message can name hosts, users and schemas.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("database unreachable\n");
        }
        return ResponseEntity.ok("ok, " + sessions.size() + " session(s) held in memory\n");
    }
}
