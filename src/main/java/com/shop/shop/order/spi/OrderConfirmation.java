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
     *       <li>이미 {@code "paid"} → {@link Outcome#ALREADY_CONFIRMED} 멱등 결과 반환 (eventPublished=false)</li>
     *       <li>{@code "pending"} 아닌 다른 상태 → {@link Outcome#REJECTED} 반환 (rejectedReason 포함)</li>
     *     </ul>
     *   </li>
     *   <li>금액 재검증 ({@code finalAmount.compareTo(paidAmount) != 0} → {@link Outcome#REJECTED})</li>
     *   <li>{@link com.shop.shop.order.domain.Order#markPaid()} 상태 전이</li>
     *   <li>OrderCompletedEvent 구성·발행 (같은 트랜잭션 Outbox 저장) → {@link Outcome#CONFIRMED}</li>
     * </ol>
     *
     * <p>주문 row 락은 이 메서드 내부에서만 획득한다.
     * payment는 orders row를 직접 잠그지 않는다(P1).
     *
     * <p><b>REJECTED는 결제 확정 불가(409-class) 비즈니스 결과를 값으로 표현한다.</b>
     * 호출부가 이를 받아 적절히 처리한다(현재 PaymentService가 OrderConfirmationConflictException으로
     * 되던져 롤백). 404(소유권)/500(금액변환)은 여전히 예외로 던진다.
     *
     * <p><b>미래 HTTP 분리 계약:</b> 이 결과 타입은 미래 HTTP 분리 시 어댑터가 그대로 채우는 계약이다.
     * confirmPaid 빈 교체만으로 동작하도록 설계되어 있다.
     *
     * @param orderId          주문 ID
     * @param requesterUserId  요청자 userId (소유권 재검증용)
     * @param paidAmount       결제된 금액 (주문 finalAmount와 비교 검증)
     * @return 확정 결과 ({@link Outcome} 포함)
     * @throws com.shop.shop.common.exception.OrderNotFoundException              타인/미존재 주문 (404)
     * @throws com.shop.shop.common.exception.AmountConversionException           금액 long 변환 실패 (500)
     */
    OrderConfirmationResult confirmPaid(long orderId, long requesterUserId, BigDecimal paidAmount);

    /**
     * 주문 확정 결과 (Entity 미노출, scalar only).
     *
     * <p>outcome 의미:
     * <ul>
     *   <li>{@link Outcome#CONFIRMED} — 정상 확정, eventPublished=true, rejectedReason=null</li>
     *   <li>{@link Outcome#ALREADY_CONFIRMED} — 이미 paid 멱등 경로, eventPublished=false, rejectedReason=null</li>
     *   <li>{@link Outcome#REJECTED} — 확정 불가 비즈니스 결과, eventPublished=false, rejectedReason 포함(비-null)</li>
     * </ul>
     *
     * @param orderId        주문 PK
     * @param orderNumber    주문 번호
     * @param outcome        확정 결과 구분
     * @param eventPublished 이벤트 발행 여부 (ALREADY_CONFIRMED·REJECTED 분기면 false)
     * @param orderedAt      주문 확정 시각
     * @param rejectedReason 거절 사유 (REJECTED일 때 비-null, 그 외 null)
     */
    record OrderConfirmationResult(
            long orderId,
            String orderNumber,
            Outcome outcome,
            boolean eventPublished,
            Instant orderedAt,
            String rejectedReason
    ) {}

    /**
     * 주문 확정 결과 구분.
     *
     * <ul>
     *   <li>{@code CONFIRMED} — 정상 pending→paid 전이 + OrderCompletedEvent 발행 완료</li>
     *   <li>{@code ALREADY_CONFIRMED} — 이미 paid 상태(멱등 경로), 재발행 없음</li>
     *   <li>{@code REJECTED} — 비-pending 상태 또는 금액 불일치로 확정 불가; rejectedReason에 사유 포함.
     *       현재 PaymentService가 이를 받아 OrderConfirmationConflictException으로 되던져 트랜잭션 롤백.
     *       미래 HTTP 분리 시 UNKNOWN(timeout) 분기를 추가하여 재시도/정산 처리 가능.</li>
     * </ul>
     */
    enum Outcome {
        CONFIRMED,
        ALREADY_CONFIRMED,
        REJECTED
    }
}
