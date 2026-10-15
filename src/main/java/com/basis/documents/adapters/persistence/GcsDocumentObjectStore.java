/* Stores immutable originals through the Google Cloud Storage SDK.
 * Uses AbstractRemoteDocumentObjectStore for tenant, size and hash checks.
 * Generation preconditions preserve write-once keys; offline tests do not prove live access.
 */
package com.basis.documents.adapters.persistence;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

/** Google Cloud Storage adapter using a generation precondition for create-if-absent. */
public final class GcsDocumentObjectStore extends AbstractRemoteDocumentObjectStore {
    private final Storage storage;
    private final String bucket;

    public GcsDocumentObjectStore(Storage storage, String bucket) {
        this.storage = Objects.requireNonNull(storage, "GCS storage client is required");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("GCS bucket is required");
        this.bucket = bucket;
    }

    @Override protected boolean createIfAbsent(String key, byte[] content) {
        BlobInfo object = BlobInfo.newBuilder(BlobId.of(bucket, key))
                .setContentType("application/octet-stream")
                .build();
        try {
            storage.createFrom(object, new ByteArrayInputStream(content), Storage.BlobWriteOption.doesNotExist());
            return true;
        } catch (StorageException e) {
            if (e.getCode() == 412) return false;
            throw e;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("document object write failed", e);
        }
    }

    @Override protected InputStream openObject(String key) {
        Blob blob = storage.get(BlobId.of(bucket, key));
        if (blob == null) return null;
        Long size = blob.getSize();
        if (size == null || size > MAX_OBJECT_BYTES) throw new IllegalStateException("stored document size unavailable or exceeds limit");
        return new ByteArrayInputStream(blob.getContent());
    }
}
