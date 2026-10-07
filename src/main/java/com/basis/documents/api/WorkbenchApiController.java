package com.basis.documents.api;

import com.basis.documents.application.WorkbenchAuth;
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
    public WorkbenchApiController(@Value("${basis.documents.token-digest:}") String tokenDigest) {
        this.tokenDigest = tokenDigest;
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
}
