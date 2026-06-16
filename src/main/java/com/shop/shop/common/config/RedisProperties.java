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
            auth = new Auth(null, null, null, null, null, null);
        }
        if (lock == null) {
            lock = new Lock(null, null);
        }
    }

    /**
     * JWT refresh token + access token blacklist + 비밀번호 재설정 토큰 key namespace / TTL.
     * 키 식별자(userId vs jti) 기준은 JWT Task에서 확정한다.
     * blacklist TTL은 access token 잔여 만료 시간으로 JWT Task에서 동적 산정 예정 — 여기 기본값은 access TTL 가정치.
     * resetTtl은 PasswordResetService.requestReset에서 실제 참조(TokenStore store TTL 출처 SSOT).
     */
    public record Auth(
            String refreshPrefix,
            Duration refreshTtl,
            String blacklistPrefix,
            Duration blacklistTtl,
            String resetPrefix,
            Duration resetTtl
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
            if (resetPrefix == null || resetPrefix.isBlank()) {
                resetPrefix = "shopcore:auth:reset:";
            }
            if (resetTtl == null) {
                resetTtl = Duration.ofMinutes(30);  // PT30M — 비밀번호 재설정 토큰 TTL
            }
        }
    }

    /**
     * 분산락 key prefix / TTL (Task 035 — Redisson 스케줄러 리더 게이트).
     *
     * <p><b>prefix</b>: 락 키 조립에 사용. 키 형식 = {@code {prefix}scheduler:{name}}.
     * 예: {@code shopcore:lock:scheduler:unpaid-order-expiry}.
     *
     * <p><b>ttl — 스케줄러 리더 게이트에 미사용(레거시 stub)</b>:
     * {@code RedissonSchedulerLeaderGuard}는 {@code tryLock(0, SECONDS)} + leaseTime 미지정(2-인자 오버로드)으로
     * Redisson watchdog 자동 갱신에 맡긴다. 고정 leaseTime(= ttl)은 작업 overrun 시 실행 중 만료 →
     * 중복 실행 재오픈이므로 리더 게이트에서 사용하지 않는다(Task 035 기술제약 3).
     * ttl 필드는 고정 lease가 필요한 다른 용도를 위해 존재하는 레거시 stub이다.
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
                ttl = Duration.ofSeconds(10);  // PT10S — 고정 lease 용도용 stub (리더 게이트에 미사용)
            }
        }
    }
}
