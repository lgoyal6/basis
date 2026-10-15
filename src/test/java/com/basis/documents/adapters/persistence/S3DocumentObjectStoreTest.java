/* Checks S3 adapter preconditions and content validation without a cloud account.
 * Runs S3DocumentObjectStore against SDK client doubles.
 * Includes valid writes and conflicts; these tests do not establish live S3 policy.
 */
package com.basis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3DocumentObjectStoreTest {
    private static final String BUCKET = "basis-documents";
    private static final String TENANT = "d2dbd18a-3c32-4e47-8fc1-751fd0243720";
    private static final byte[] CONTENT = "financial evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test void createsWithIfNoneMatchAndServerSideEncryption() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        var store = new S3DocumentObjectStore(client, BUCKET);

        var stored = store.put(TENANT, hash(CONTENT), new ByteArrayInputStream(CONTENT), CONTENT.length);

        assertThat(stored.key()).isEqualTo(TENANT + "/" + hash(CONTENT) + ".blob");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
    }

    @Test void aPreconditionConflictReadsAndVerifiesTheExistingObject() throws Exception {
        S3Client client = mock(S3Client.class);
        S3Exception precondition = s3Error(412);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(precondition);
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> body = mock(ResponseInputStream.class);
        when(body.readNBytes(AbstractRemoteDocumentObjectStore.MAX_OBJECT_BYTES + 1)).thenReturn(CONTENT);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(body);

        var result = new S3DocumentObjectStore(client, BUCKET)
                .put(TENANT, hash(CONTENT), new ByteArrayInputStream(CONTENT), CONTENT.length);

        assertThat(result.size()).isEqualTo(CONTENT.length);
        verify(client).getObject(any(GetObjectRequest.class));
    }

    @Test void rejectsWrongHashesAndCorruptExistingObjects() throws Exception {
        S3Client client = mock(S3Client.class);
        var store = new S3DocumentObjectStore(client, BUCKET);
        assertThatThrownBy(() -> store.put(TENANT, "0".repeat(64), new ByteArrayInputStream(CONTENT), CONTENT.length))
                .isInstanceOf(IllegalArgumentException.class);
        S3Exception precondition = s3Error(412);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(precondition);
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> body = mock(ResponseInputStream.class);
        when(body.readNBytes(AbstractRemoteDocumentObjectStore.MAX_OBJECT_BYTES + 1)).thenReturn(new byte[CONTENT.length]);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(body);
        assertThatThrownBy(() -> store.put(TENANT, hash(CONTENT), new ByteArrayInputStream(CONTENT), CONTENT.length))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash mismatch");
    }

    @Test void readValidatesTheContentAddressAndReturnsOriginalBytes() throws Exception {
        S3Client client = mock(S3Client.class);
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> body = mock(ResponseInputStream.class);
        when(body.readNBytes(AbstractRemoteDocumentObjectStore.MAX_OBJECT_BYTES + 1)).thenReturn(CONTENT);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(body);

        try (var input = new S3DocumentObjectStore(client, BUCKET).get(TENANT, hash(CONTENT))) {
            assertThat(input.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test void rejectsInvalidTenantAndMissingBucket() {
        S3Client client = mock(S3Client.class);
        assertThatThrownBy(() -> new S3DocumentObjectStore(client, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3DocumentObjectStore(client, BUCKET).get("../tenant", "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static S3Exception s3Error(int code) {
        return (S3Exception) S3Exception.builder().statusCode(code).message("conditional request failed").build();
    }
}
