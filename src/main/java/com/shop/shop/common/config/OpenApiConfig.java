package com.shop.shop.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI(springdoc) 설정 — info·전역 JWT Bearer SecurityScheme + /api/v1/** 그룹 스캔.
 *
 * <p>게이트(@ConditionalOnProperty): springdoc.api-docs.enabled 프로퍼티 기준. matchIfMissing=true
 * 이므로 프로퍼티가 부재하면 빈을 등록한다. prod는 application-prod.yml이 enabled=false를
 * 명시하므로 빈 미등록 + 핸들러 미등록.
 * (메모리 선례 shop-core-tests-no-active-profile-gating: 게이트를 @Profile에 걸지 않고 프로퍼티로 둔다.)
 *
 * <p>테스트에서 빈이 등록되는 실제 메커니즘: 풀 @SpringBootTest는
 * src/test/resources/application.yml이 main application.yml을 통째로 가린다
 * (ActuatorSecurityTest.java 주석이 명문화한 동일 shadowing — management 노출도 같은 이유로
 * @SpringBootTest(properties=...)로 명시 주입). 따라서 테스트 classpath에는 springdoc 섹션이
 * 존재하지 않는다. enabled 프로퍼티가 부재(=missing)하므로 (1) matchIfMissing=true가 이 빈을 등록하고,
 * (2) springdoc 라이브러리 자체 기본값(api-docs.enabled/swagger-ui.enabled 미설정 시 true)이
 * 핸들러를 활성화한다. 스모크 테스트는 암묵 의존하지 않도록 properties=enabled=true를 명시 주입한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled", matchIfMissing = true)
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    /**
     * 전역 메타 + JWT Bearer 보안 스키마.
     * 전역 SecurityRequirement(addSecurityItem)로 Swagger UI "Authorize"가 노출된다.
     * 공개 엔드포인트는 컨트롤러에서 @SecurityRequirements(빈 배열)로 요구를 비운다.
     */
    @Bean
    public OpenAPI shopCoreOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("shop-core API")
                        .version("v1")
                        .description("shop-core REST API (/api/v1/**). 인증: JWT Bearer (POST /api/v1/auth/login 발급)."))
                // 서버는 상대 경로 — 배포 호스트/포트에 무관(역프록시 뒤에서도 동작)
                .addServersItem(new io.swagger.v3.oas.models.servers.Server().url("/"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Authorization: Bearer <access_token>")))
                // 전역 보안 요구 — 보호 엔드포인트 기본값. 공개 엔드포인트는 핸들러에서 해제.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * /api/v1/** 만 문서화 — View(@Controller HTML) 핸들러를 스펙에서 제외.
     * (springdoc 기본은 모든 @Controller/@RestController 스캔이므로 명시 제한 필수.)
     */
    @Bean
    public GroupedOpenApi apiV1Group() {
        return GroupedOpenApi.builder()
                .group("api-v1")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
