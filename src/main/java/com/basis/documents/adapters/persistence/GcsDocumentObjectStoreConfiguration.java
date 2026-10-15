/* Creates the optional GCS object-store beans used by document ingest.
 * Selects GcsDocumentObjectStore only when the gcs property is set.
 * Uses Application Default Credentials; the local adapter remains the default.
 */
package com.basis.documents.adapters.persistence;

import com.basis.documents.application.DocumentObjectStore;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "basis.documents", name = "object-store", havingValue = "gcs")
class GcsDocumentObjectStoreConfiguration {
    @Bean(destroyMethod = "close")
    Storage basisGcsStorage(@Value("${basis.documents.gcs.project-id:}") String projectId,
                            @Value("${basis.documents.gcs.endpoint:}") String endpoint) {
        var builder = StorageOptions.newBuilder();
        if (!projectId.isBlank()) builder.setProjectId(projectId);
        if (!endpoint.isBlank()) builder.setHost(endpoint);
        return builder.build().getService();
    }

    @Bean
    DocumentObjectStore basisGcsDocumentObjectStore(
            Storage basisGcsStorage,
            @Value("${basis.documents.gcs.bucket}") String bucket) {
        return new GcsDocumentObjectStore(basisGcsStorage, bucket);
    }
}
