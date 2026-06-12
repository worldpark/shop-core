package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.PaymentEventResolutionException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link OrderPaymentReader} 구현체 (package-private).
 *
 * <p>order 내부 {@code service} 패키지에 배치한다.
 * payment는 인터페이스({@link OrderPaymentReader})만 참조하며, 이 구현체를 직접 알지 못한다(P1).
 *
 * <p>메서드 분리(#3):
 * <ul>
 *   <li>{@link #getPayableOrder} — 결제 전용. 이벤트 완결성 사전검증(productId/연락처) 포함. 409 가능.</li>
 *   <li>{@link #getOrderSnapshot} — 상태 조회 전용. 완결성 검증 없음. 409를 던지지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
class OrderPaymentReaderImpl implements OrderPaymentReader {

    private static final String CURRENCY_KRW = "KRW";

    private final OrderRepository orderRepository;
    private final ProductOrderCatalog productOrderCatalog;
    private final MemberDirectory memberDirectory;

    /**
     * {@inheritDoc}
     *
     * <p>이벤트 완결성 사전검증:
     * <ol>
     *   <li>전 항목 variantId → productId 해석 (product.spi)</li>
     *   <li>member 연락처 존재 (member.spi)</li>
     * </ol>
     */
    @Override
    public OrderPaymentView getPayableOrder(long orderId, long requesterUserId) {
        // findWithItemsOnlyByIdAndUserId: items만 fetch (optionValues 제외 — MultipleBagFetchException 회피)
        // 결제 이벤트 완결성 검증은 variantId만 필요하므로 items fetch만으로 충분하다.
        Order order = orderRepository.findWithItemsOnlyByIdAndUserId(orderId, requesterUserId)
                .orElseThrow(OrderNotFoundException::new);

        // 이벤트 완결성 사전검증 (P2) — 결제 경로 전용
        validateEventCompleteness(order);

        return new OrderPaymentView(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getStatus(),
                order.getFinalAmount(),
                CURRENCY_KRW
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>productId/연락처 해석을 수행하지 않으므로 409로 깨지지 않는다(#3).
     */
    @Override
    public OrderSnapshotView getOrderSnapshot(long orderId, long requesterUserId) {
        Order order = orderRepository.findByIdAndUserId(orderId, requesterUserId)
                .orElseThrow(OrderNotFoundException::new);

        return new OrderSnapshotView(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getStatus(),
                order.getFinalAmount(),
                CURRENCY_KRW
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>orders row PESSIMISTIC_WRITE 잠금 후 소유권 검증(#4).
     * PaymentService.cancel의 쓰기 트랜잭션 안에서 호출되므로 락이 트랜잭션 전체에 걸쳐 유효하다.
     * items 조회 없음 — 재고 복원에 필요한 items 로딩은 OrderCancellationImpl이 별도 수행.
     */
    @Override
    @Transactional
    public OrderSnapshotView getOrderForCancel(long orderId, long requesterUserId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(OrderNotFoundException::new);

        if (!order.getUserId().equals(requesterUserId)) {
            throw new OrderNotFoundException();
        }

        return new OrderSnapshotView(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getStatus(),
                order.getFinalAmount(),
                CURRENCY_KRW
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>시스템 만료 전용 — orders row PESSIMISTIC_WRITE 잠금, 소유권 검증 없음(시스템 주도).
     * PaymentService.expirePendingOrder의 쓰기 트랜잭션 안에서 호출.
     * 락은 같은 트랜잭션의 cancelByExpiry까지 유효(재진입, R3).
     */
    @Override
    @Transactional
    public OrderSnapshotView getOrderForExpiry(long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(OrderNotFoundException::new);

        // 소유권 검증 없음 — 시스템 주도

        return new OrderSnapshotView(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getStatus(),
                order.getFinalAmount(),
                CURRENCY_KRW
        );
    }

    /**
     * 이벤트 완결성 사전검증.
     *
     * <p>variantId → productId 해석 불가 또는 member 연락처 미존재 시
     * {@link PaymentEventResolutionException}(409)을 던진다.
     * PG 호출 전에 실패해 "승인 후 이벤트 구성 불가"를 원천 차단한다(P2).
     *
     * @param order 주문 (items 즉시 로딩된 상태)
     */
    private void validateEventCompleteness(Order order) {
        // 1. 전 항목 variantId → productId 해석 가능 여부
        List<Long> variantIds = order.getItems().stream()
                .map(item -> item.getVariantId())
                .filter(variantId -> variantId != null)
                .distinct()
                .toList();

        // variantId가 null인 항목 존재 시 해석 불가
        long nullVariantCount = order.getItems().stream()
                .filter(item -> item.getVariantId() == null)
                .count();
        if (nullVariantCount > 0) {
            log.warn("결제 완결성 검증 실패(variantId null): orderId={}, nullCount={}",
                    order.getId(), nullVariantCount);
            throw new PaymentEventResolutionException();
        }

        if (!variantIds.isEmpty()) {
            List<OrderableVariantSnapshot> snapshots = productOrderCatalog.getOrderableSnapshots(variantIds);
            Map<Long, OrderableVariantSnapshot> snapshotMap = snapshots.stream()
                    .collect(Collectors.toMap(OrderableVariantSnapshot::variantId, Function.identity()));

            Collection<Long> missingVariants = variantIds.stream()
                    .filter(id -> !snapshotMap.containsKey(id))
                    .toList();
            if (!missingVariants.isEmpty()) {
                log.warn("결제 완결성 검증 실패(productId 해석 불가): orderId={}, missingVariantCount={}",
                        order.getId(), missingVariants.size());
                throw new PaymentEventResolutionException();
            }
        }

        // 2. member 연락처 존재 여부
        try {
            memberDirectory.findContactByUserId(order.getUserId());
        } catch (IllegalStateException e) {
            log.warn("결제 완결성 검증 실패(연락처 해석 불가): orderId={}, userId={}",
                    order.getId(), order.getUserId());
            throw new PaymentEventResolutionException();
        }
    }
}
