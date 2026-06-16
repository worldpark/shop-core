package com.shop.shop.common.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Cloudflare R2(S3 호환) 저장소 S3Client 빈 설정.
 *
 * <p>{@code shop.storage.type=r2}일 때만 활성화된다.
 *
 * <p>S3Client 구성:
 * <ul>
 *   <li>endpointOverride: R2 S3 호환 엔드포인트 ({@code https://{accountId}.r2.cloudflarestorage.com})</li>
 *   <li>region: {@code auto} (Cloudflare R2 전용)</li>
 *   <li>credentials: StorageProperties.r2에서 환경변수로 주입된 access-key/secret-key</li>
 *   <li>path-style access 활성화: account-id 엔드포인트에서 버킷명을 path로 전달 (가상호스트 DNS 의존 회피)</li>
 * </ul>
 *
 * <p>S3Client는 싱글톤 빈으로 컨테이너 수명과 일치한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "shop.storage", name = "type", havingValue = "r2")
public class R2StorageConfig {

    /**
     * Cloudflare R2용 S3Client 빈.
     *
     * <p>credentials는 StorageProperties.r2에서 읽어오며, access-key/secret-key는
     * 반드시 환경변수로만 주입된다 (코드·yml 평문 금지).
     */
    @Bean
    public S3Client s3Client(StorageProperties storageProperties) {
        StorageProperties.R2 r2 = storageProperties.getR2();
        return S3Client.builder()
                .endpointOverride(URI.create(r2.getEndpoint()))
                .region(Region.of(r2.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.getAccessKey(), r2.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
