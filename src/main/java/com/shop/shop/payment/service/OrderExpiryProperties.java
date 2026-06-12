package com.shop.shop.payment.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 미결제 주문 만료(TTL) 스케줄러 설정 바인딩.
 *
 * <p>prefix = "shop.order.pending-expiry" — application.yml의 {@code shop.order.pending-expiry} 블록과 1:1 대응.
 * {@link RedisProperties} 패턴(C11) 동일 방식 — compact constructor로 기본값 처리.
 *
 * <p>설정 의미:
 * <ul>
 *   <li>{@code enabled} — 스케줄러 활성화 여부 (테스트 컨텍스트에서 false로 비활성)</li>
 *   <li>{@code ttl} — 미결제 만료 TTL (기본 30분: PT30M)</li>
 *   <li>{@code interval} — 스케줄러 fixed-delay 주기 (기본 1분: PT1M)</li>
 *   <li>{@code batchLimit} — 1회 실행당 최대 처리 건수 (기본 100)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "shop.order.pending-expiry")
public record OrderExpiryProperties(
        boolean enabled,
        Duration ttl,
        Duration interval,
        int batchLimit
) {

    /** compact constructor — null/0 시 기본값 폴백 (RedisProperties 패턴). */
    public OrderExpiryProperties {
        if (ttl == null) {
            ttl = Duration.ofMinutes(30);   // PT30M — 30분 미결제 만료
        }
        if (interval == null) {
            interval = Duration.ofMinutes(1);  // PT1M — 1분 주기(fixed-delay)
        }
        if (batchLimit <= 0) {
            batchLimit = 100;  // 1회 최대 100건
        }
    }
}
