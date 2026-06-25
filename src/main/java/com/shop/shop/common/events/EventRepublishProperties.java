package com.shop.shop.common.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 미완료 발행 상시 회복 스케줄러 설정 바인딩 (Task 064).
 *
 * <p>prefix = "shop.events.republish" — application.yml의 {@code shop.events.republish} 블록과 1:1 대응.
 * 선례 {@code OrderExpiryProperties} 패턴 — compact constructor로 기본값 처리.
 *
 * <p>설정 의미:
 * <ul>
 *   <li>{@code enabled} — 스케줄러 활성화 여부 (테스트 컨텍스트에서 false로 비활성)</li>
 *   <li>{@code interval} — 스케줄러 fixed-delay 주기 (기본 1분: PT1M)</li>
 *   <li>{@code olderThan} — 재제출 대상 최소 경과시간 (기본 2분: PT2M).
 *       반드시 프로듀서 delivery.timeout.ms(기본 120000ms=2분) 이상이어야 한다.
 *       그렇지 않으면 프로듀서가 아직 자동 재시도 중인 발행을 스케줄러가 중복 재제출한다.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "shop.events.republish")
public record EventRepublishProperties(boolean enabled, Duration interval, Duration olderThan) {

    /** compact constructor — null 시 기본값 폴백 (OrderExpiryProperties 패턴). */
    public EventRepublishProperties {
        if (interval == null) {
            interval = Duration.ofMinutes(1);  // PT1M — 1분 주기(fixed-delay)
        }
        if (olderThan == null) {
            olderThan = Duration.ofMinutes(2);  // PT2M — delivery.timeout(120000ms) 경계
        }
    }
}
