package com.shop.shop.payment.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 미결제 주문 만료 스케줄러 설정.
 *
 * <p>역할:
 * <ol>
 *   <li>{@link OrderExpiryProperties} 바인딩 활성화 ({@code @EnableConfigurationProperties})</li>
 *   <li>{@code @EnableScheduling} 활성화 — 코드베이스 최초 {@code @Scheduled} 도입(C10)</li>
 *   <li>{@link Clock} 빈 제공 — 스케줄러가 주입받아 테스트에서 고정 클록으로 대체 가능</li>
 * </ol>
 *
 * <p><b>활성화 가드 ({@code @ConditionalOnProperty})</b>:
 * {@code shop.order.pending-expiry.enabled=true}일 때만 이 설정이 로드된다.
 * {@code src/test/resources/application.yml}에 {@code enabled: false}를 명시해
 * 테스트 컨텍스트에서 {@code @EnableScheduling}과 스케줄러 빈이 생성되지 않는다(verification-gate §4).
 *
 * <p>RedisConfig 패턴(C11) 참조.
 */
@Configuration
@ConditionalOnProperty(prefix = "shop.order.pending-expiry", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OrderExpiryProperties.class)
@EnableScheduling
public class OrderExpirySchedulingConfig {

    /**
     * 시스템 UTC 클록 빈.
     *
     * <p>스케줄러({@link UnpaidOrderExpiryScheduler})가 {@code threshold = clock.instant() - ttl} 계산에 사용.
     * 단위 테스트에서는 고정 {@link Clock}을 직접 주입해 threshold를 제어한다.
     *
     * @return UTC 기준 시스템 클록
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
