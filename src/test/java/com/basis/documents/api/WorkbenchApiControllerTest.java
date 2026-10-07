package com.basis.documents.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.basis.documents.application.*;
import com.basis.documents.domain.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkbenchApiControllerTest {
    private final ReviewRepository repository = mock(ReviewRepository.class);
    private static final String BODY = "{\"expectedVersion\":0,\"action\":\"APPROVE\",\"actor\":\"spoof\",\"reason\":\"checked source\"}";

    private MockMvc api(WorkbenchAuth.Role role) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("test-token".getBytes(StandardCharsets.UTF_8)));
        return MockMvcBuilders.standaloneSetup(new WorkbenchApiController(digest, "tenant", "reviewer", role, repository)).build();
    }

    @Test void credentialsCannotChooseAnotherTenantOrReviewerRole() throws Exception {
        var api = api(WorkbenchAuth.Role.AUDITOR);
        api.perform(get("/v1/workbench/whoami").header("Authorization", "Bearer test-token").header("X-Tenant-Id", "other"))
                .andExpect(status().isUnauthorized());
        api.perform(post("/v1/workbench/review-tasks/task/decisions").header("Authorization", "Bearer test-token")
                        .header("X-Tenant-Id", "tenant").header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test void actorComesFromAuthenticatedConfigurationAndKeyIsForwarded() throws Exception {
        when(repository.decide(eq("tenant"), eq("task"), eq("key"), any())).thenThrow(new IllegalStateException("idempotency key conflict"));
        api(WorkbenchAuth.Role.REVIEWER).perform(post("/v1/workbench/review-tasks/task/decisions")
                        .header("Authorization", "Bearer test-token").header("X-Tenant-Id", "tenant")
                        .header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isConflict());
        verify(repository).decide(eq("tenant"), eq("task"), eq("key"), argThat(command -> command.actor().equals("reviewer")
                && command.action() == ReviewAction.APPROVE && command.reason().equals("checked source")));
    }

    @Test void missingKeyAndActionAreRejectedBeforeMutation() throws Exception {
        var api = api(WorkbenchAuth.Role.REVIEWER);
        api.perform(post("/v1/workbench/review-tasks/task/decisions").header("Authorization", "Bearer test-token")
                .header("X-Tenant-Id", "tenant").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());
        api.perform(post("/v1/workbench/review-tasks/task/decisions").header("Authorization", "Bearer test-token")
                .header("X-Tenant-Id", "tenant").header("Idempotency-Key", "key")
                .contentType("application/json").content("{\"expectedVersion\":0,\"reason\":\"checked\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test void missingCredentialsAreRejectedBeforeRepositoryAccess() throws Exception {
        api(WorkbenchAuth.Role.REVIEWER).perform(get("/v1/workbench/review-tasks/task"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }
}
