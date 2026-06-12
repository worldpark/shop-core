package com.shop.shop.order.spi;

import java.time.Instant;
import java.util.List;

/**
 * 미결제 만료 대상 주문 스칼라 조회 published port (order 소유).
 *
 * <p>payment 모듈(스케줄러)이 만료 후보 id 목록을 얻기 위해 사용하는 포트.
 * order 내부(domain/repository)를 직접 참조하지 않고 이 포트를 통해서만 접근한다(P1).
 *
 * <p>id만(스칼라) 반환 — Entity 적재·과도한 락 방지.
 * 실제 만료 처리(락 + 상태 전이)는 {@link OrderPaymentReader#getOrderForExpiry}와
 * {@link OrderCancellation#cancelByExpiry}로 수행한다.
 *
 * <p>모듈 의존: {@code payment → order.spi}(기존 방향 유지). 순환 없음.
 */
public interface OrderExpiryReader {

    /**
     * TTL 초과 pending 주문 id 목록 조회.
     *
     * <p>{@code status = 'pending' AND created_at < :threshold} 조건, {@code created_at/id} 오름차순(오래된 것 먼저).
     * {@code limit}으로 1회 처리량 제한(대량 락·롱 트랜잭션 방지).
     *
     * <p>threshold는 호출자(스케줄러)가 {@code Instant.now() - ttl}로 계산해 주입한다.
     * DB {@code now()} 하드코딩 금지 — 클록 주입으로 테스트 제어 가능.
     *
     * @param threshold 만료 판정 기준 시각 ({@code now - ttl})
     * @param limit     최대 반환 건수 (배치 한도)
     * @return 만료 대상 주문 id 목록 (스칼라, 최대 limit건)
     */
    List<Long> findExpiredPendingOrderIds(Instant threshold, int limit);
}
