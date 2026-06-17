package com.shop.shop.web.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 관리자 대시보드 SSE 스케줄 broadcaster 설정.
 *
 * <p>역할:
 * <ol>
 *   <li>{@code @EnableScheduling} 활성화 — order-expiry 스케줄러({@code OrderExpirySchedulingConfig})와 독립된 전용 활성 지점</li>
 *   <li>{@code @ConditionalOnProperty} 가드 — {@code shop.admin.dashboard.sse.enabled=true}일 때만 로드</li>
 * </ol>
 *
 * <p><b>활성화 가드</b>: {@code shop.admin.dashboard.sse.enabled=true}(기본 on, {@code matchIfMissing=true}).
 * {@code src/test/resources/application.yml}에 {@code shop.admin.dashboard.sse.enabled: false}를 명시해
 * 테스트 컨텍스트에서 {@code @EnableScheduling}과 스케줄러 빈이 생성되지 않는다.
 * order-expiry의 {@code pending-expiry.enabled=false} 패턴과 동일.
 *
 * <p>{@code @EnableScheduling}은 컨테이너에 복수여도 무해하나, 본 기능 소유의 활성 지점을 두어
 * order-expiry on/off와 독립시킨다(plan §2.3 결정).
 */
@Configuration
@ConditionalOnProperty(
        prefix = "shop.admin.dashboard.sse",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableScheduling
public class AdminDashboardSseSchedulingConfig {
}
