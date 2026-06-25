package com.shop.shop.common.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 미완료 발행 상시 회복 스케줄러 설정 (Task 064).
 *
 * <p>역할:
 * <ol>
 *   <li>{@link EventRepublishProperties} 바인딩 활성화 ({@code @EnableConfigurationProperties})</li>
 *   <li>{@code @EnableScheduling} 활성화 — 064 단독 ON 보장(D2).
 *       {@code OrderExpirySchedulingConfig}가 {@code shop.order.pending-expiry.enabled}로 게이팅되므로
 *       "order OFF + republish ON" 컨텍스트에서도 스케줄링이 활성된다.
 *       Spring {@code @EnableScheduling} 다중 선언은 단일 {@code ScheduledAnnotationBeanPostProcessor}로
 *       수렴(idempotent) — OrderExpiry와 공존해도 무해.</li>
 * </ol>
 *
 * <p><b>활성화 가드 ({@code @ConditionalOnProperty})</b>:
 * {@code shop.events.republish.enabled=true}일 때만 이 설정이 로드된다.
 * {@code src/test/resources/application.yml}에 {@code enabled: false}를 명시해
 * 테스트 컨텍스트에서 {@code @EnableScheduling}과 스케줄러 빈이 생성되지 않는다(verification-gate §4).
 *
 * <p>Clock 빈 불필요 — older-than은 publication_date 기준을 Modulith가 내부 계산(OrderExpiry와 차이점).
 * 선례 {@code OrderExpirySchedulingConfig} 패턴 계승.
 */
@Configuration
@ConditionalOnProperty(prefix = "shop.events.republish", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EventRepublishProperties.class)
@EnableScheduling
public class EventRepublishSchedulingConfig {
    // 게이팅 + Properties 바인딩 + 스케줄링 활성 전용. @Bean 없음
    // (선례 OrderExpiry는 Clock 빈을 뒀으나 064는 Clock 불필요 — older-than은 publication_date 기준을 Modulith가 내부 계산).
}
