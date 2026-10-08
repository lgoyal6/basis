package com.basis.documents.adapters.persistence;

import com.basis.documents.application.DocumentObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Local development adapter; production replaces this bean with encrypted object storage. */
@Component
public final class LocalDocumentObjectStore implements DocumentObjectStore {
    private final Path root;
    public LocalDocumentObjectStore(@Value("${basis.documents.object-root:${java.io.tmpdir}/basis-documents}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }
    @Override public Stored put(String tenantId, String sha256, InputStream input, long size) {
        if (!tenantId.matches("[0-9a-fA-F-]{36}") || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid object identity");
        if (size < 0) throw new IllegalArgumentException("negative object size");
        try {
            Path dir = root.resolve(tenantId).normalize();
            if (!dir.startsWith(root)) throw new IllegalArgumentException("unsafe object path");
            Files.createDirectories(dir);
            Path destination = dir.resolve(sha256 + ".blob");
            if (Files.exists(destination)) {
                if (Files.size(destination) != size) throw new IllegalStateException("content hash already exists with another size");
                return new Stored(tenantId + "/" + sha256 + ".blob", size);
            }
            Path temporary = Files.createTempFile(dir, sha256, ".part");
            long copied = 0;
            try (InputStream in = input; OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) { copied += n; if (copied > size) throw new IllegalArgumentException("document exceeded declared size"); out.write(buffer, 0, n); }
                if (copied != size) throw new IllegalArgumentException("document size changed while reading");
                out.flush();
            }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.FileAlreadyExistsException e) { Files.deleteIfExists(temporary); }
            return new Stored(tenantId + "/" + sha256 + ".blob", size);
        } catch (IOException e) { throw new IllegalStateException("document object write failed", e); }
    }
    @Override public InputStream get(String tenantId, String sha256) {
        try { return Files.newInputStream(root.resolve(tenantId).resolve(sha256 + ".blob")); }
        catch (IOException e) { throw new IllegalStateException("document object unavailable", e); }
    }
}
