package com.basis.documents.api;

import com.basis.documents.application.WorkbenchAuth;
import com.basis.documents.application.ReviewRepository;
import com.basis.documents.application.ReviewWorkflow;
import com.basis.documents.domain.ReviewAction;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Small authenticated boundary kept out of the privacy-preserving public web profile. */
@RestController
@Profile("workbench")
@RequestMapping("/v1/workbench")
public final class WorkbenchApiController {
    private final String tokenDigest;
    private final ReviewRepository reviews;
    private final String tenantId;
    private final String actor;
    private final WorkbenchAuth.Role role;

    public WorkbenchApiController(@Value("${basis.documents.token-digest:}") String tokenDigest,
                                  @Value("${basis.documents.tenant-id:}") String tenantId,
                                  @Value("${basis.documents.actor:}") String actor,
                                  @Value("${basis.documents.role:AUDITOR}") WorkbenchAuth.Role role,
                                  ReviewRepository reviews) {
        this.tokenDigest = tokenDigest;
        this.tenantId = tenantId;
        this.actor = actor;
        this.role = role;
        this.reviews = reviews;
    }

    private WorkbenchAuth.Principal authenticate(String authorization, String requestedTenant) {
        if (tenantId.isBlank() || actor.isBlank() || !tenantId.equals(requestedTenant)) throw new SecurityException("unauthorized");
        return WorkbenchAuth.authenticate(authorization, tokenDigest, tenantId, role);
    }

    @GetMapping("/whoami")
    public ResponseEntity<Map<String, Object>> whoami(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        try {
            var principal = authenticate(authorization, tenant);
            return ResponseEntity.ok(Map.of("tenantId", principal.tenantId(), "role", principal.role().name()));
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
    }

    @GetMapping("/review-tasks/{taskId}")
    public ResponseEntity<?> task(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                  @org.springframework.web.bind.annotation.PathVariable String taskId) {
        try {
            var principal = authenticate(authorization, tenant);
            var task = reviews.findTask(principal.tenantId(), taskId);
            var fact = reviews.findFact(principal.tenantId(), task.factId());
            return ResponseEntity.ok(Map.of("task", task, "fact", fact));
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/review-tasks/{taskId}/decisions")
    public ResponseEntity<?> decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @org.springframework.web.bind.annotation.PathVariable String taskId,
                                    @org.springframework.web.bind.annotation.RequestBody DecisionRequest request) {
        try {
            var principal = authenticate(authorization, tenant);
            WorkbenchAuth.requireReviewer(principal);
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) throw new IllegalArgumentException("Idempotency-Key must be 1..128 characters");
            if (request.action() == null) throw new IllegalArgumentException("action is required");
            var decision = reviews.decide(principal.tenantId(), taskId, idempotencyKey,
                    new ReviewWorkflow.Command(request.expectedVersion(), request.action(), actor, request.reason(), request.correction()));
            return ResponseEntity.ok(Map.of("task", decision.task(), "fact", decision.fact(), "action", decision.action()));
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        } catch (IllegalStateException e) {
            if (e.getMessage().startsWith("idempotency key")) {
                return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.notFound().build();
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record DecisionRequest(int expectedVersion, ReviewAction action, String actor, String reason, BigDecimal correction) { }
}
