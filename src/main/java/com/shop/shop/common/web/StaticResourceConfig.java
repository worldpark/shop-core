package com.shop.shop.common.web;

import com.shop.shop.common.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 정적 자산 서빙 설정.
 *
 * <p>{@code shop.storage.public-prefix} (예: /assets/**) 경로로 들어오는 요청을
 * {@code shop.storage.root} 디렉터리의 파일로 서빙한다.
 *
 * <p>SecurityConfig View 체인에서 {@code /assets/**}를 permitAll로 설정해야
 * 인증 없이 공개 조회가 가능하다.
 *
 * <p>{@link StorageProperties} 빈이 존재할 때만 활성화된다. {@code @WebMvcTest} 슬라이스처럼
 * {@code @EnableConfigurationProperties}가 로드되지 않는 환경에서 컨텍스트 로드 실패를 방지한다.
 *
 * <p>type=r2일 때는 비활성화된다. R2 프로파일에서 {@code public-prefix=""}이면 {@code /**} ResourceHandler가
 * 등록되어 전체 라우팅을 가로채는 문제를 방지한다. R2에서는 공개 URL이 R2 도메인에서 직접 서빙된다.
 */
@Configuration
@ConditionalOnBean(StorageProperties.class)
@ConditionalOnProperty(prefix = "shop.storage", name = "type", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class StaticResourceConfig implements WebMvcConfigurer {

    private final StorageProperties storageProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String prefix = storageProperties.getPublicPrefix();
        String root = storageProperties.getRoot();

        // root 경로를 file: URI로 변환 (끝에 / 추가)
        String fileLocation = toFileUri(root);

        registry.addResourceHandler(prefix + "/**")
                .addResourceLocations(fileLocation);
    }

    private String toFileUri(String path) {
        if (path == null || path.isBlank()) {
            return "file:./uploads/";
        }
        String normalized = path.replace("\\", "/");
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        if (!normalized.startsWith("file:")) {
            normalized = "file:" + normalized;
        }
        return normalized;
    }
}
