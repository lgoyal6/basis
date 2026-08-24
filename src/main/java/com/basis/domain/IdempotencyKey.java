package com.basis.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The key that makes re-importing a statement a no-op instead of a duplicate.
 *
 * <p>Stored as {@code txn.idempotency_key BYTEA UNIQUE}, so the uniqueness is the
 * database's job and not the application's. Derived from the source row's identifying
 * content, which means the same broker row always produces the same key, whichever
 * import batch carried it.
 *
 * <p>{@code equals} and {@code hashCode} are written by hand because a record over a
 * {@code byte[]} would compare by array identity, and two equal keys would look different.
 */
public record IdempotencyKey(byte[] bytes) {

    public IdempotencyKey {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("idempotency key must not be empty");
        }
        bytes = bytes.clone();
    }

    /** SHA-256 over the parts, length-delimited so that concatenation is unambiguous. */
    public static IdempotencyKey of(String... parts) {
        MessageDigest digest = sha256();
        for (String part : parts) {
            byte[] encoded = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(encoded);
            digest.update((byte) 0x1f);
        }
        return new IdempotencyKey(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdempotencyKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(bytes);
    }
}
