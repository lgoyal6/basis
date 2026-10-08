package com.basis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDocumentObjectStoreTest {
    @TempDir Path root;
    private final String tenant = UUID.randomUUID().toString();
    private final byte[] bytes = "fact,value\nrevenue,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private String hash() throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    @Test void wrongHashAndTruncatedInputNeverPublishOrLeaveParts() throws Exception {
        var store = new LocalDocumentObjectStore(root.toString());
        assertThatThrownBy(() -> store.put(tenant, "0".repeat(64), new ByteArrayInputStream(bytes), bytes.length)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put(tenant, hash(), new ByteArrayInputStream(bytes), bytes.length + 1)).isInstanceOf(IllegalArgumentException.class);
        try (var files = Files.list(root.resolve(tenant))) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void concurrentPublicationIsImmutableAndComplete() throws Exception {
        var store = new LocalDocumentObjectStore(root.toString());
        var digest = hash();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<String> write = () -> { start.await(); return store.put(tenant, digest, new ByteArrayInputStream(bytes), bytes.length).key(); };
            var a = pool.submit(write); var b = pool.submit(write); start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        try (var in = store.get(tenant, digest)) { assertThat(in.readAllBytes()).isEqualTo(bytes); }
        try (var files = Files.list(root.resolve(tenant))) { assertThat(files.toList()).hasSize(1); }
    }

    @Test void readsRejectTraversalAndSymlinkObjects() throws Exception {
        var store = new LocalDocumentObjectStore(root.toString());
        assertThatThrownBy(() -> store.get("../escape", hash())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get(tenant, "../escape")).isInstanceOf(IllegalArgumentException.class);
        Files.createDirectory(root.resolve(tenant));
        Files.createSymbolicLink(root.resolve(tenant).resolve(hash() + ".blob"), root.resolve("outside"));
        assertThatThrownBy(() -> store.get(tenant, hash())).isInstanceOf(IllegalStateException.class);
    }

    @Test void anExistingCorruptObjectIsNeverReportedAsSuccessful() throws Exception {
        var store = new LocalDocumentObjectStore(root.toString());
        Files.createDirectory(root.resolve(tenant));
        Files.write(root.resolve(tenant).resolve(hash() + ".blob"), new byte[bytes.length]);
        assertThatThrownBy(() -> store.put(tenant, hash(), new ByteArrayInputStream(bytes), bytes.length)).isInstanceOf(IllegalStateException.class);
    }
}
