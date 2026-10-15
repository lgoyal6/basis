/* Checks remote document bytes against tenant keys and content hashes.
 * S3DocumentObjectStore and GcsDocumentObjectStore share this contract.
 * Reads are capped at 50 MiB; conflicting writes require stored-byte verification.
 */
package com.basis.documents.adapters.persistence;

import com.basis.documents.application.DocumentObjectStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Shared content-addressing and write-once checks for remote object-store adapters. */
abstract class AbstractRemoteDocumentObjectStore implements DocumentObjectStore {
    static final int MAX_OBJECT_BYTES = 50 * 1024 * 1024;

    /** Returns true only when this call created the key. Existing keys must remain untouched. */
    protected abstract boolean createIfAbsent(String key, byte[] content);

    /** Returns null only when the key does not exist. The caller closes a returned stream. */
    protected abstract InputStream openObject(String key);

    @Override public final Stored put(String tenantId, String sha256, InputStream input, long size) {
        String key = key(tenantId, sha256);
        if (input == null) throw new IllegalArgumentException("document input is required");
        byte[] content = readInput(input, size);
        verifyUpload(content, sha256, size);
        boolean created = createIfAbsent(key, content);
        if (!created) verifyExisting(key, sha256, size);
        return new Stored(key, size);
    }

    @Override public final InputStream get(String tenantId, String sha256) {
        String key = key(tenantId, sha256);
        byte[] content = readExisting(key);
        verify(content, sha256, content.length);
        return new ByteArrayInputStream(content);
    }

    private void verifyExisting(String key, String sha256, long expectedSize) {
        byte[] existing = readExisting(key);
        verify(existing, sha256, expectedSize);
    }

    private byte[] readExisting(String key) {
        InputStream stream = openObject(key);
        if (stream == null) throw new IllegalStateException("document object unavailable");
        try (stream) {
            byte[] content = stream.readNBytes(MAX_OBJECT_BYTES + 1);
            if (content.length > MAX_OBJECT_BYTES) throw new IllegalStateException("stored document exceeds size limit");
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("document object read failed", e);
        }
    }

    private static byte[] readInput(InputStream input, long size) {
        if (size <= 0 || size > MAX_OBJECT_BYTES) throw new IllegalArgumentException("unsupported object size");
        try (input) {
            byte[] content = input.readNBytes((int) size + 1);
            if (content.length != size) throw new IllegalArgumentException("document size changed while reading");
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("document input read failed", e);
        }
    }

    private static void verify(byte[] content, String sha256, long expectedSize) {
        if (content.length != expectedSize) throw new IllegalStateException("stored document size mismatch");
        if (!sha256.equals(HexFormat.of().formatHex(digest().digest(content)))) {
            throw new IllegalStateException("stored document hash mismatch");
        }
    }

    private static void verifyUpload(byte[] content, String sha256, long expectedSize) {
        if (content.length != expectedSize) throw new IllegalArgumentException("document size changed while reading");
        if (!sha256.equals(HexFormat.of().formatHex(digest().digest(content)))) {
            throw new IllegalArgumentException("document content hash mismatch");
        }
    }

    private static String key(String tenantId, String sha256) {
        try {
            if (tenantId == null || !UUID.fromString(tenantId).toString().equals(tenantId)) {
                throw new IllegalArgumentException("invalid tenant identity");
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid tenant identity", e);
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid object hash");
        }
        return tenantId + "/" + sha256 + ".blob";
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
