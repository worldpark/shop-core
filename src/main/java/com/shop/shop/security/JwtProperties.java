package com.shop.shop.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 설정 바인딩.
 * prefix = "shop.security.jwt" — application.yml의 shop.security.jwt 블록과 1:1 대응.
 *
 * <p>secret: 환경변수 SHOP_SECURITY_JWT_SECRET으로 주입. 코드/yml 하드코딩 금지.
 *   부재 또는 길이 부족(256bit = 32자 이상) 시 애플리케이션 기동 실패.
 *
 * <p>TTL SSOT: refresh/access TTL의 단일 진실 소스는 이 클래스다.
 *   Redis 키 TTL도 이 값을 사용한다 (RedisProperties의 *-ttl은 namespace 설명용 잔존값, 코드 미참조).
 */
@ConfigurationProperties(prefix = "shop.security.jwt")
public record JwtProperties(
        String secret,
        Duration accessTtl,
        Duration refreshTtl,
        String issuer
) {

    /** HS256은 256bit(32bytes, Base64로 약 43자) 이상의 secret을 요구한다. */
    private static final int MIN_SECRET_LENGTH = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret이 설정되지 않았습니다. 환경변수 SHOP_SECURITY_JWT_SECRET을 설정하세요. " +
                    "(shop.security.jwt.secret)"
            );
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret이 너무 짧습니다. 최소 " + MIN_SECRET_LENGTH + "자 이상이어야 합니다. " +
                    "(HS256 = 256bit 이상 권장)"
            );
        }
        if (accessTtl == null) {
            accessTtl = Duration.ofMinutes(30);
        }
        if (refreshTtl == null) {
            refreshTtl = Duration.ofDays(14);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "shop-core";
        }
    }
}
