package com.shop.shop.order.service;

import com.shop.shop.order.repository.OrderItemSalesRepository;
import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@link SellerSalesStatsPort} 구현체.
 *
 * <p>order 모듈 내부 비공개 서비스. 소유권을 모른 채 주어진 variantId 집합의 완료 판매를 집계한다.
 * 소유권 검사는 web 계층(호출자) 책임이다.
 *
 * <p>판매 인정 상태: paid / preparing / shipping / delivered (lowercase — Order.status 스칼라와 일치).
 * cancelled / refunded / pending 제외.
 */
@Service
@RequiredArgsConstructor
class SellerSalesStatsService implements SellerSalesStatsPort {

    /**
     * 판매 인정 상태 집합 (lowercase — Order.status DB 값과 일치).
     */
    private static final Set<String> COUNTED_STATUSES = Set.of(
            "paid", "preparing", "shipping", "delivered"
    );

    private final OrderItemSalesRepository orderItemSalesRepository;

    /**
     * {@inheritDoc}
     *
     * <p>variantIds가 비면 DB 조회 없이 빈 리스트 즉시 반환.
     */
    @Override
    @Transactional(readOnly = true)
    public List<VariantSalesAggregate> aggregateByVariantIds(Collection<Long> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return Collections.emptyList();
        }
        return orderItemSalesRepository.aggregateSalesByVariantIds(variantIds, COUNTED_STATUSES);
    }
}
