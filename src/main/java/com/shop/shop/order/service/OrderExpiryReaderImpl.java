package com.shop.shop.order.service;

import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderExpiryReader;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * {@link OrderExpiryReader} 구현체 (package-private).
 *
 * <p>order 내부 {@code service} 패키지에 배치한다.
 * payment 모듈(스케줄러)은 인터페이스({@link OrderExpiryReader})만 참조하며,
 * 이 구현체를 직접 알지 못한다(P1).
 *
 * <p>{@link OrderRepository#findExpiredPendingOrderIds}에 위임해 id 스칼라 목록을 반환한다.
 * Entity 적재 없음 — 과도한 락·메모리 방지.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
class OrderExpiryReaderImpl implements OrderExpiryReader {

    private final OrderRepository orderRepository;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Long> findExpiredPendingOrderIds(Instant threshold, int limit) {
        return orderRepository.findExpiredPendingOrderIds(threshold, PageRequest.of(0, limit));
    }
}
