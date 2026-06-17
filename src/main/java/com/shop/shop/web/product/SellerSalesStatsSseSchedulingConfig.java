package com.shop.shop.web.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 판매자 판매 현황 SSE 스케줄 broadcaster 설정.
 *
 * <p>역할:
 * <ol>
 *   <li>{@code @EnableScheduling} 활성화 — order-expiry·admin SSE 스케줄러와 독립된 전용 활성 지점</li>
 *   <li>{@code @ConditionalOnProperty} 가드 — {@code shop.seller.sales.sse.enabled=true}일 때만 로드</li>
 * </ol>
 *
 * <p><b>활성화 가드</b>: {@code shop.seller.sales.sse.enabled=true}(기본 on, {@code matchIfMissing=true}).
 * {@code src/test/resources/application.yml}에 {@code shop.seller.sales.sse.enabled: false}를 명시해
 * 테스트 컨텍스트에서 {@code @EnableScheduling}과 스케줄러 빈이 생성되지 않는다.
 *
 * <p>046 {@link com.shop.shop.web.admin.AdminDashboardSseSchedulingConfig} 패턴과 동일.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "shop.seller.sales.sse",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableScheduling
public class SellerSalesStatsSseSchedulingConfig {
}
