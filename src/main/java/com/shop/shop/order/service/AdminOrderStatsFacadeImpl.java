package com.shop.shop.order.service;

import com.shop.shop.order.repository.OrderItemSalesRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.AdminOrderStatsFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * {@link AdminOrderStatsFacade} 구현체.
 *
 * <p>order 모듈 내부 비공개 서비스. 관리자 통계 대시보드용 집계만 담당한다.
 * 소유권 검사 없음(전체 통계, admin 전용).
 *
 * <p>완료 판매 상태: paid / preparing / shipping / delivered.
 * Task 040 {@code SellerSalesStatsService.COUNTED_STATUSES}와 값이 동일하나,
 * 해당 상수는 package-private이므로 여기서 독립 정의한다.
 */
@Service
@RequiredArgsConstructor
class AdminOrderStatsFacadeImpl implements AdminOrderStatsFacade {

    /**
     * 완료 판매 인정 상태 집합 (lowercase — Order.status DB 값과 일치).
     * cancelled / refunded / pending 제외.
     */
    private static final Set<String> COMPLETED = Set.of(
            "paid", "preparing", "shipping", "delivered"
    );

    private final OrderRepository orderRepository;
    private final OrderItemSalesRepository orderItemSalesRepository;

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public long countOrdersSince(Instant threshold) {
        return orderRepository.countByCreatedAtAfter(threshold);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public long countRefundedSince(Instant threshold) {
        return orderRepository.countByStatusAndCreatedAtAfter("refunded", threshold);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public List<Long> distinctSoldVariantIdsSince(Instant threshold) {
        return orderItemSalesRepository.distinctSoldVariantIdsSince(threshold, COMPLETED);
    }
}
