package com.basis.documents.adapters.persistence;

import com.basis.documents.application.DocumentObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Content-addressed local adapter. The configured directory must be private to the service. */
@Component
public final class LocalDocumentObjectStore implements DocumentObjectStore {
    private final Path root;

    public LocalDocumentObjectStore(@Value("${basis.documents.object-root:${java.io.tmpdir}/basis-documents}") String root) {
        try {
            Path configured = Path.of(root).toAbsolutePath().normalize();
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
        } catch (IOException e) { throw new IllegalStateException("document storage root unavailable", e); }
    }

    @Override public Stored put(String tenantId, String sha256, InputStream input, long size) {
        Path destination = objectPath(tenantId, sha256);
        if (size <= 0 || size > 50L * 1024 * 1024) throw new IllegalArgumentException("unsupported object size");
        Path temporary = null;
        try {
            Path directory = destination.getParent();
            try { Files.createDirectory(directory); } catch (FileAlreadyExistsException ignored) { }
            requireDirectory(directory);
            temporary = Files.createTempFile(directory, sha256, ".part");
            MessageDigest digest = digest();
            long copied = 0;
            try (InputStream in = input; FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var out = Channels.newOutputStream(file);
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    copied += n;
                    if (copied > size) throw new IllegalArgumentException("document exceeded declared size");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
                if (copied != size) throw new IllegalArgumentException("document size changed while reading");
                if (!sha256.equals(HexFormat.of().formatHex(digest.digest()))) throw new IllegalArgumentException("document content hash mismatch");
                file.force(true);
            }
            // A hard link publishes a complete inode atomically and cannot replace an existing
            // name. ATOMIC_MOVE alone permits replacement on some supported filesystems.
            try { Files.createLink(destination, temporary); } catch (FileAlreadyExistsException ignored) { }
            verify(destination, sha256, size);
            try (FileChannel directoryHandle = FileChannel.open(destination.getParent(), StandardOpenOption.READ)) { directoryHandle.force(true); }
            return new Stored(tenantId + "/" + sha256 + ".blob", size);
        } catch (IOException e) { throw new IllegalStateException("document object write failed", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { throw new IllegalStateException("document temporary object cleanup failed", e); }
            }
        }
    }

    @Override public InputStream get(String tenantId, String sha256) {
        Path path = objectPath(tenantId, sha256);
        try {
            requireDirectory(path.getParent());
            verify(path, sha256, Files.size(path));
            return Channels.newInputStream(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) { throw new IllegalStateException("document object unavailable", e); }
    }

    private Path objectPath(String tenant, String hash) {
        try {
            if (tenant == null || !UUID.fromString(tenant).toString().equals(tenant)) throw new IllegalArgumentException("invalid tenant identity");
        } catch (RuntimeException e) { throw new IllegalArgumentException("invalid tenant identity", e); }
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid object hash");
        return root.resolve(tenant).resolve(hash + ".blob");
    }

    private static void requireDirectory(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("unsafe document storage directory");
    }

    private static void verify(Path path, String hash, long size) throws IOException {
        MessageDigest digest = digest();
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (file.size() != size || size > 50L * 1024 * 1024) throw new IllegalStateException("stored document size mismatch");
            var in = Channels.newInputStream(file);
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        if (!hash.equals(HexFormat.of().formatHex(digest.digest()))) throw new IllegalStateException("stored document hash mismatch");
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
