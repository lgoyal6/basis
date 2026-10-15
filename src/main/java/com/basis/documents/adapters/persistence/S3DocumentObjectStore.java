/* Stores immutable originals through the AWS S3 SDK.
 * Uses AbstractRemoteDocumentObjectStore for tenant, size and hash checks.
 * Conditional create prevents replacement and requests provider-side encryption.
 */
package com.basis.documents.adapters.persistence;

import java.io.InputStream;
import java.util.Objects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** S3 object-store adapter using a conditional create and provider-side at-rest encryption. */
public final class S3DocumentObjectStore extends AbstractRemoteDocumentObjectStore {
    private final S3Client client;
    private final String bucket;

    public S3DocumentObjectStore(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "S3 client is required");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("S3 bucket is required");
        this.bucket = bucket;
    }

    @Override protected boolean createIfAbsent(String key, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .ifNoneMatch("*")
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build();
        try {
            client.putObject(request, RequestBody.fromBytes(content));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 412) return false;
            throw e;
        }
    }

    @Override protected InputStream openObject(String key) {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }
}
