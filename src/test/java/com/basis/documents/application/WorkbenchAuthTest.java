package com.basis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class WorkbenchAuthTest {
    @Test void authenticatesDigestAndPreservesTenantRole() throws Exception {
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("secret".getBytes(StandardCharsets.UTF_8)));
        var principal = WorkbenchAuth.authenticate("Bearer secret", digest, "tenant", WorkbenchAuth.Role.REVIEWER);
        assertThat(principal.tenantId()).isEqualTo("tenant");
        WorkbenchAuth.requireReviewer(principal);
    }
    @Test void rejectsWrongTokenAndInsufficientRole() throws Exception {
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("secret".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> WorkbenchAuth.authenticate("Bearer wrong", digest, "tenant", WorkbenchAuth.Role.UPLOADER)).isInstanceOf(SecurityException.class);
        var uploader = WorkbenchAuth.authenticate("Bearer secret", digest, "tenant", WorkbenchAuth.Role.UPLOADER);
        assertThatThrownBy(() -> WorkbenchAuth.requireReviewer(uploader)).isInstanceOf(SecurityException.class);
    }
}
