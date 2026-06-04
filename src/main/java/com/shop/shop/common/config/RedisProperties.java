package com.shop.shop.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * shop-core Redis key namespace / TTL 설정 바인딩.
 * prefix = "shop.redis" — application.yml의 shop.redis 블록과 1:1 대응.
 *
 * 용도:
 * - auth: JWT refresh token 저장 + access token blacklist
 * - lock: 분산락 key prefix / TTL
 *
 * 키 조립(prefix + ":" + id)과 실제 Redis 저장/조회는 후속 Task(JWT 인증 Task / 분산락 Task)에서 구현.
 * 이 클래스는 설계·설정 기반만 제공한다.
 *
 * namespace 격리: 모든 prefix는 "shopcore:" 로 시작. notification prefix("notif:")는 이 클래스에 존재하지 않으며,
 * 상대 프로젝트 prefix를 참조하는 코드는 0 — Redis 교차 접근/통신 구조적 차단.
 * DB index 격리: spring.data.redis.database=0 (notification은 database=1).
 */
@ConfigurationProperties(prefix = "shop.redis")
public record RedisProperties(
        Auth auth,
        Lock lock
) {

    /** 기본값 폴백 — 환경변수/yml 미지정 시 사용. SecurityUserProperties 패턴과 동일. */
    public RedisProperties {
        if (auth == null) {
            auth = new Auth(null, null, null, null);
        }
        if (lock == null) {
            lock = new Lock(null, null);
        }
    }

    /**
     * JWT refresh token + access token blacklist key namespace / TTL.
     * 키 식별자(userId vs jti) 기준은 JWT Task에서 확정한다.
     * blacklist TTL은 access token 잔여 만료 시간으로 JWT Task에서 동적 산정 예정 — 여기 기본값은 access TTL 가정치.
     */
    public record Auth(
            String refreshPrefix,
            Duration refreshTtl,
            String blacklistPrefix,
            Duration blacklistTtl
    ) {

        public Auth {
            if (refreshPrefix == null || refreshPrefix.isBlank()) {
                refreshPrefix = "shopcore:auth:refresh:";
            }
            if (refreshTtl == null) {
                refreshTtl = Duration.ofDays(14);   // P14D — refresh token 만료와 정합
            }
            if (blacklistPrefix == null || blacklistPrefix.isBlank()) {
                blacklistPrefix = "shopcore:auth:blacklist:";
            }
            if (blacklistTtl == null) {
                blacklistTtl = Duration.ofMinutes(30);  // PT30M — access token 가정 TTL
            }
        }
    }

    /**
     * 분산락 key prefix / TTL.
     * 실제 락 획득/해제 구현과 Redisson 도입 여부는 분산락 Task에서 결정.
     * 데드락 방지를 위해 짧은 TTL(PT10S) 기본값 사용.
     */
    public record Lock(
            String prefix,
            Duration ttl
    ) {

        public Lock {
            if (prefix == null || prefix.isBlank()) {
                prefix = "shopcore:lock:";
            }
            if (ttl == null) {
                ttl = Duration.ofSeconds(10);  // PT10S — 데드락 방지 짧은 TTL
            }
        }
    }
}
