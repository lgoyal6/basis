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
    private final ReviewWorkflow workflow = new ReviewWorkflow();

    public WorkbenchApiController(@Value("${basis.documents.token-digest:}") String tokenDigest, ReviewRepository reviews) {
        this.tokenDigest = tokenDigest;
        this.reviews = reviews;
    }

    @GetMapping("/whoami")
    public ResponseEntity<Map<String, Object>> whoami(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        try {
            var principal = WorkbenchAuth.authenticate(authorization, tokenDigest, tenant, WorkbenchAuth.Role.UPLOADER);
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
            var principal = WorkbenchAuth.authenticate(authorization, tokenDigest, tenant, WorkbenchAuth.Role.REVIEWER);
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
                                    @org.springframework.web.bind.annotation.PathVariable String taskId,
                                    @org.springframework.web.bind.annotation.RequestBody DecisionRequest request) {
        try {
            var principal = WorkbenchAuth.authenticate(authorization, tokenDigest, tenant, WorkbenchAuth.Role.REVIEWER);
            var task = reviews.findTask(principal.tenantId(), taskId);
            var fact = reviews.findFact(principal.tenantId(), task.factId());
            var decision = workflow.decide(task, fact, request.expectedVersion(), request.action(), request.actor(),
                    request.reason(), request.correction());
            reviews.appendDecision(principal.tenantId(), decision);
            return ResponseEntity.ok(Map.of("task", decision.task(), "fact", decision.fact(), "action", decision.action()));
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record DecisionRequest(int expectedVersion, ReviewAction action, String actor, String reason, BigDecimal correction) { }
}
