package com.shop.shop.order.spi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주문 확정 published port (order 소유).
 *
 * <p>payment 모듈이 주문 확정(pending→paid 전이 + OrderCompletedEvent 발행)을 위임하는 포트.
 * order 내부(domain/repository/service)를 직접 참조하지 않고 이 포트만 사용한다(P1).
 *
 * <p>구현체({@code OrderConfirmationImpl})는 order 내부 {@code service} 패키지에 배치한다.
 *
 * <p>017에서 시그니처 불변.
 */
public interface OrderConfirmation {

    /**
     * 주문 확정 — orders row 비관락 + 권위 재검증 + pending→paid + OrderCompletedEvent 발행.
     *
     * <p>처리:
     * <ol>
     *   <li>orders row {@code PESSIMISTIC_WRITE} 비관락</li>
     *   <li>소유권 재검증 (불일치 → 404 존재 은닉)</li>
     *   <li>status 분기:
     *     <ul>
     *       <li>이미 {@code "paid"} → 멱등 결과 반환 (confirmed=true, eventPublished=false)</li>
     *       <li>{@code "pending"} 아닌 다른 상태 → {@link com.shop.shop.common.exception.OrderConfirmationConflictException}(409)</li>
     *     </ul>
     *   </li>
     *   <li>금액 재검증 ({@code finalAmount.compareTo(paidAmount) != 0} → 409)</li>
     *   <li>{@link com.shop.shop.order.domain.Order#markPaid()} 상태 전이</li>
     *   <li>OrderCompletedEvent 구성·발행 (같은 트랜잭션 Outbox 저장)</li>
     * </ol>
     *
     * <p>주문 row 락은 이 메서드 내부에서만 획득한다.
     * payment는 orders row를 직접 잠그지 않는다(P1).
     *
     * @param orderId          주문 ID
     * @param requesterUserId  요청자 userId (소유권 재검증용)
     * @param paidAmount       결제된 금액 (주문 finalAmount와 비교 검증)
     * @return 확정 결과
     * @throws com.shop.shop.common.exception.OrderNotFoundException              타인/미존재 주문 (404)
     * @throws com.shop.shop.common.exception.OrderConfirmationConflictException  상태 충돌 (409)
     * @throws com.shop.shop.common.exception.AmountConversionException           금액 long 변환 실패 (500)
     */
    OrderConfirmationResult confirmPaid(long orderId, long requesterUserId, BigDecimal paidAmount);

    /**
     * 주문 확정 결과 (Entity 미노출, scalar only).
     *
     * @param orderId        주문 PK
     * @param orderNumber    주문 번호
     * @param confirmed      확정 성공 여부 (이미 paid면 true)
     * @param eventPublished 이벤트 발행 여부 (이미 paid 멱등 분기면 false)
     * @param orderedAt      주문 확정 시각
     */
    record OrderConfirmationResult(
            long orderId,
            String orderNumber,
            boolean confirmed,
            boolean eventPublished,
            Instant orderedAt
    ) {}
}
