package com.basis.documents.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Authentication boundary for API adapters. Only token digests cross into storage. */
public final class WorkbenchAuth {
    public enum Role { UPLOADER, REVIEWER, AUDITOR }
    public record Principal(String tenantId, Role role) { }
    private WorkbenchAuth() { }

    public static Principal authenticate(String authorization, String expectedDigest, String tenantId, Role role) {
        if (authorization == null || !authorization.startsWith("Bearer ") || expectedDigest == null) throw new SecurityException("unauthorized");
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank()) throw new SecurityException("unauthorized");
        String digest = digest(token);
        if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII), expectedDigest.getBytes(StandardCharsets.US_ASCII))) throw new SecurityException("unauthorized");
        if (tenantId == null || role == null) throw new SecurityException("unauthorized");
        return new Principal(tenantId, role);
    }

    public static void requireReviewer(Principal principal) {
        if (principal == null || principal.role() != Role.REVIEWER) throw new SecurityException("reviewer role required");
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
