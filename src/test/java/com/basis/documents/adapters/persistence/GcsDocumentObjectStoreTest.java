/* Checks GCS adapter preconditions and content validation without a cloud account.
 * Runs GcsDocumentObjectStore against SDK client doubles.
 * Includes valid writes and conflicts; these tests do not establish live GCS policy.
 */
package com.basis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GcsDocumentObjectStoreTest {
    private static final String BUCKET = "basis-documents";
    private static final String TENANT = "d2dbd18a-3c32-4e47-8fc1-751fd0243720";
    private static final byte[] CONTENT = "financial evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test void createsOnlyWhenTheGenerationDoesNotExist() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.createFrom(any(BlobInfo.class), any(java.io.InputStream.class), any(Storage.BlobWriteOption[].class)))
                .thenReturn(mock(Blob.class));
        var stored = new GcsDocumentObjectStore(storage, BUCKET)
                .put(TENANT, hash(CONTENT), new ByteArrayInputStream(CONTENT), CONTENT.length);

        assertThat(stored.key()).isEqualTo(TENANT + "/" + hash(CONTENT) + ".blob");
        ArgumentCaptor<Storage.BlobWriteOption[]> options = ArgumentCaptor.forClass(Storage.BlobWriteOption[].class);
        verify(storage).createFrom(eq(BlobInfo.newBuilder(BlobId.of(BUCKET, stored.key()))
                .setContentType("application/octet-stream").build()), any(java.io.InputStream.class), options.capture());
        assertThat(options.getValue()).hasSize(1);
    }

    @Test void aGenerationPreconditionConflictReadsAndVerifiesTheExistingObject() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.createFrom(any(BlobInfo.class), any(java.io.InputStream.class), any(Storage.BlobWriteOption[].class)))
                .thenThrow(new StorageException(412, "precondition failed"));
        Blob existing = blob(CONTENT);
        when(storage.get(BlobId.of(BUCKET, TENANT + "/" + hash(CONTENT) + ".blob"))).thenReturn(existing);

        var result = new GcsDocumentObjectStore(storage, BUCKET)
                .put(TENANT, hash(CONTENT), new ByteArrayInputStream(CONTENT), CONTENT.length);

        assertThat(result.size()).isEqualTo(CONTENT.length);
        verify(storage).get(BlobId.of(BUCKET, result.key()));
    }

    @Test void rejectsWrongHashesAndCorruptExistingObjects() throws Exception {
        Storage storage = mock(Storage.class);
        var store = new GcsDocumentObjectStore(storage, BUCKET);
        assertThatThrownBy(() -> store.put(TENANT, "0".repeat(64), new ByteArrayInputStream(CONTENT), CONTENT.length))
                .isInstanceOf(IllegalArgumentException.class);
        when(storage.createFrom(any(BlobInfo.class), any(java.io.InputStream.class), any(Storage.BlobWriteOption[].class)))
                .thenThrow(new StorageException(412, "precondition failed"));
        Blob corrupt = blob(new byte[CONTENT.length]);
        when(storage.get(any(BlobId.class))).thenReturn(corrupt);
        assertThatThrownBy(() -> store.put(TENANT, hash(CONTENT), new ByteArrayInputStream(CONTENT), CONTENT.length))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash mismatch");
    }

    @Test void readValidatesTheContentAddressAndReturnsOriginalBytes() throws Exception {
        Storage storage = mock(Storage.class);
        Blob existing = blob(CONTENT);
        when(storage.get(BlobId.of(BUCKET, TENANT + "/" + hash(CONTENT) + ".blob"))).thenReturn(existing);

        try (var input = new GcsDocumentObjectStore(storage, BUCKET).get(TENANT, hash(CONTENT))) {
            assertThat(input.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test void rejectsInvalidTenantAndMissingBucket() {
        Storage storage = mock(Storage.class);
        assertThatThrownBy(() -> new GcsDocumentObjectStore(storage, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GcsDocumentObjectStore(storage, BUCKET).get("../tenant", "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Blob blob(byte[] bytes) {
        Blob blob = mock(Blob.class);
        when(blob.getSize()).thenReturn((long) bytes.length);
        when(blob.getContent()).thenReturn(bytes);
        return blob;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
