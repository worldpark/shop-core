package com.shop.shop.order.spi;

import java.time.Instant;

/**
 * 주문 취소 published port (order 소유).
 *
 * <p>payment 모듈이 주문 취소(상태 전이 + 재고 복원 + OrderCancelledEvent 발행)를 위임하는 포트.
 * order 내부(domain/repository/service)를 직접 참조하지 않고 이 포트만 사용한다(P1).
 * {@link OrderConfirmation}과 대칭 설계 — 같은 락 패턴·404 재검증·Outcome 값.
 *
 * <p>구현체({@code OrderCancellationImpl})는 order 내부 {@code service} 패키지에 배치한다.
 *
 * <p>모듈 의존: {@code payment → order.spi}(기존 방향 유지). {@code order → payment.spi} 금지(순환).
 */
public interface OrderCancellation {

    /**
     * 주문 취소 — orders row 비관락 권위 재검증 + 종결 전이 + 재고 복원 + OrderCancelledEvent 발행.
     *
     * <p>처리:
     * <ol>
     *   <li>orders row {@code PESSIMISTIC_WRITE} 비관락 (이미 locked reader가 잡은 락 재진입)</li>
     *   <li>소유권 재검증 (불일치 → 404 존재 은닉)</li>
     *   <li>status 분기:
     *     <ul>
     *       <li>이미 {@code "cancelled"}/{@code "refunded"} → {@link Outcome#ALREADY_CANCELLED} 멱등 반환</li>
     *       <li>이행단계({@code "preparing"}/{@code "shipping"}/{@code "delivered"}) → {@link Outcome#REJECTED}</li>
     *       <li>{@code "pending"}/{@code "paid"} → 진행</li>
     *     </ul>
     *   </li>
     *   <li>종결 전이: {@code refundInfo.refunded()}면 {@link com.shop.shop.order.domain.Order#markRefunded()}(→refunded),
     *       아니면 {@link com.shop.shop.order.domain.Order#markCancelled()}(→cancelled) — #3</li>
     *   <li>재고 복원: order_items variantId 오름차순 {@code inventory.spi.increase}, null skip+log</li>
     *   <li>OrderCancelledEvent 구성·발행 (같은 트랜잭션 Outbox) → {@link Outcome#CANCELLED}</li>
     * </ol>
     *
     * <p><b>PaymentService 2단계에서 이미 이행단계·종결 상태를 차단</b>하므로
     * 정상 흐름에서 {@link Outcome#REJECTED}/{@link Outcome#ALREADY_CANCELLED}는 발생하지 않는다.
     * 발생 시 PaymentService가 {@code IllegalStateException}(500, 락 불변식 위반)으로 처리한다.
     *
     * @param orderId          주문 ID
     * @param requesterUserId  요청자 userId (소유권 재검증용)
     * @param refundInfo       payment이 결정한 환불 정보
     * @return 취소 결과 ({@link Outcome} 포함)
     * @throws com.shop.shop.common.exception.OrderNotFoundException 타인/미존재 주문 (404)
     * @throws com.shop.shop.common.exception.AmountConversionException 금액 long 변환 실패 (500)
     */
    OrderCancellationResult cancel(long orderId, long requesterUserId, RefundInfo refundInfo);

    /**
     * 환불 정보 — payment 모듈이 환불 결정 후 전달.
     *
     * @param refunded        환불 여부 (결제완료 취소=true, 미결제 취소=false)
     * @param refundedAmount  환불 금액 (long, KRW=원). 미결제 취소=0
     * @param currency        통화 코드 (예: "KRW")
     */
    record RefundInfo(
            boolean refunded,
            long refundedAmount,
            String currency
    ) {}

    /**
     * 주문 취소 결과 (Entity 미노출, scalar only).
     *
     * <p>outcome 의미:
     * <ul>
     *   <li>{@link Outcome#CANCELLED} — 정상 취소, eventPublished=true. orderStatus=cancelled/refunded</li>
     *   <li>{@link Outcome#ALREADY_CANCELLED} — 이미 cancelled/refunded(멱등), eventPublished=false</li>
     *   <li>{@link Outcome#REJECTED} — 이행단계 등 취소 불가, rejectedReason 포함</li>
     * </ul>
     *
     * @param orderId         주문 PK
     * @param orderNumber     주문 번호
     * @param outcome         취소 결과 구분
     * @param orderStatus     취소 후 주문 상태 ("cancelled" 또는 "refunded")
     * @param eventPublished  이벤트 발행 여부
     * @param cancelledAt     취소 처리 시각 (ALREADY_CANCELLED 시 null 가능)
     * @param rejectedReason  거절 사유 (REJECTED일 때 비-null, 그 외 null)
     */
    record OrderCancellationResult(
            long orderId,
            String orderNumber,
            Outcome outcome,
            String orderStatus,
            boolean eventPublished,
            Instant cancelledAt,
            String rejectedReason
    ) {}

    /**
     * 주문 취소 결과 구분 ({@link OrderConfirmation.Outcome}과 대칭).
     *
     * <ul>
     *   <li>{@code CANCELLED} — 정상 cancelled/refunded 종결 전이 + OrderCancelledEvent 발행 완료</li>
     *   <li>{@code ALREADY_CANCELLED} — 이미 cancelled/refunded 상태(멱등 경로), 재복원·재발행 없음</li>
     *   <li>{@code REJECTED} — 이행단계 등 취소 불가; rejectedReason에 사유 포함.
     *       정상 흐름에서는 PaymentService 2단계가 선판정해 이 값이 반환되지 않는다.
     *       반환 시 PaymentService가 IllegalStateException(500, 락 불변식 위반)으로 처리.</li>
     * </ul>
     */
    enum Outcome {
        CANCELLED,
        ALREADY_CANCELLED,
        REJECTED
    }
}
