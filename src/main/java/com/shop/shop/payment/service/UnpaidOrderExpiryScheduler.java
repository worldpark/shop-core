package com.shop.shop.payment.service;

import com.shop.shop.order.spi.OrderExpiryReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 미결제 주문 만료(TTL) 스케줄러 (package-private).
 *
 * <p>주기적으로 TTL 초과 pending 주문을 감지해 {@link PaymentService#expirePendingOrder}로 위임한다.
 * 스케줄러(루프)와 만료 단위({@code @Transactional})는 <b>별도 빈</b>으로 분리한다(R1 — self-invocation 함정 차단).
 *
 * <p>루프 메서드({@link #expireUnpaidOrders})는 {@code @Transactional}을 붙이지 않는다.
 * 각 주문은 빈 경계를 넘어 {@link PaymentService#expirePendingOrder}를 호출해 독립 트랜잭션으로 커밋된다(R1).
 *
 * <p><b>활성화 가드 ({@code @ConditionalOnProperty})</b>:
 * {@code shop.order.pending-expiry.enabled=true}일 때만 빈으로 등록된다.
 * 테스트 컨텍스트({@code src/test/resources/application.yml}: {@code enabled: false})에서는
 * 이 빈이 생성되지 않아 백그라운드 실행이 없다(verification-gate §4).
 *
 * <p>주문 단위 예외 격리: 한 주문 처리 실패가 다른 주문 처리를 막지 않는다(try/catch 격리, 로깅).
 *
 * <p>가상스레드 대비: {@code ThreadLocal} 직접 사용 없음, 블로킹 I/O는 Service/Infra 경계(CLAUDE.md).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.order.pending-expiry", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class UnpaidOrderExpiryScheduler {

    private final PaymentService paymentService;
    private final OrderExpiryReader orderExpiryReader;
    private final OrderExpiryProperties properties;
    private final Clock clock;

    /**
     * 미결제 주문 만료 주기 실행.
     *
     * <p>{@code @Transactional} 미부착(R1) — 루프 전체가 한 트랜잭션이 되면 주문별 격리가 깨진다.
     * 각 {@link PaymentService#expirePendingOrder} 호출이 독립 {@code @Transactional} 커밋.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>{@code threshold = clock.instant() - ttl} 계산</li>
     *   <li>{@link OrderExpiryReader#findExpiredPendingOrderIds(Instant, int)} — id 스칼라 조회</li>
     *   <li>각 id마다 {@code try/catch}로 격리하며 {@link PaymentService#expirePendingOrder} 위임</li>
     *   <li>처리 건수·후보 수 로깅</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${shop.order.pending-expiry.interval:PT1M}")
    public void expireUnpaidOrders() {
        Instant threshold = clock.instant().minus(properties.ttl());

        log.debug("미결제 만료 스케줄 시작: threshold={}, batchLimit={}", threshold, properties.batchLimit());

        List<Long> ids = orderExpiryReader.findExpiredPendingOrderIds(threshold, properties.batchLimit());

        if (ids.isEmpty()) {
            log.debug("만료 대상 주문 없음");
            return;
        }

        int processed = 0;
        for (Long orderId : ids) {
            try {
                paymentService.expirePendingOrder(orderId);
                processed++;
            } catch (Exception e) {
                log.warn("만료 처리 실패(다음 주문 계속): orderId={}", orderId, e);
            }
        }

        log.info("미결제 만료 스케줄 종료: candidates={}, processed={}", ids.size(), processed);
    }
}
