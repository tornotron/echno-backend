package org.tornotron.echno_backend.common.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class SpacesConfig {

    @Value("${digital-ocean.uri}")
    private String spaces;

    @Value("${digital-ocean.access-key-id}")
    private String access_key_id;

    @Value("${digital-ocean.secret-access-key}")
    private String secret_access_key;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(spaces))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        access_key_id,
                                        secret_access_key
                                )
                        )
                )
                .region(Region.US_EAST_1)
                .build();
    }
}
