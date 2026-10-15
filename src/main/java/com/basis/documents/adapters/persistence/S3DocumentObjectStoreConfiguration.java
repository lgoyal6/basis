/* Creates the optional S3 object-store beans used by document ingest.
 * Selects S3DocumentObjectStore only when the s3 property is set.
 * Keeps region, endpoint and credential-chain behavior in the SDK boundary.
 */
package com.basis.documents.adapters.persistence;

import com.basis.documents.application.DocumentObjectStore;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "basis.documents", name = "object-store", havingValue = "s3")
class S3DocumentObjectStoreConfiguration {
    @Bean(destroyMethod = "close")
    S3Client basisS3Client(@Value("${basis.documents.s3.region}") String region,
                           @Value("${basis.documents.s3.endpoint:}") String endpoint,
                           @Value("${basis.documents.s3.path-style-access:false}") boolean pathStyle) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    DocumentObjectStore basisS3DocumentObjectStore(
            S3Client basisS3Client,
            @Value("${basis.documents.s3.bucket}") String bucket) {
        return new S3DocumentObjectStore(basisS3Client, bucket);
    }
}
