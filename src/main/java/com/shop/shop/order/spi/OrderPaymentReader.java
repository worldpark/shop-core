package com.shop.shop.order.spi;

import java.math.BigDecimal;

/**
 * 결제용 주문 스냅샷 조회 published port (order 소유).
 *
 * <p>payment 모듈이 order 내부(domain/repository/service)를 직접 참조하지 않고
 * 이 포트를 통해서만 주문 정보에 접근한다(architecture-rule P1).
 *
 * <p>메서드 3개 분리:
 * <ul>
 *   <li>{@link #getPayableOrder} — 결제(pay) 전용. 이벤트 완결성 사전검증 포함. 409 가능.</li>
 *   <li>{@link #getOrderSnapshot} — 상태 조회 전용. 완결성 검증 없음. 404만 가능.</li>
 *   <li>{@link #getOrderForCancel} — 취소 전용. orders row PESSIMISTIC_WRITE 잠금. 환불 결정 전 호출(#4).</li>
 * </ul>
 *
 * <p>소유권 검증: 타 사용자 주문·미존재 모두 {@link com.shop.shop.common.exception.OrderNotFoundException}(404 존재 은닉).
 * order/member/product Entity 노출 금지 — scalar record만 반환.
 *
 * <p>017에서 시그니처 불변.
 */
public interface OrderPaymentReader {

    /**
     * 결제(pay) 전용 주문 스냅샷 조회.
     *
     * <p>소유권 검증(404 존재 은닉) + 이벤트 완결성 사전검증:
     * <ul>
     *   <li>전 항목 variantId → productId 해석 가능 여부 (product.spi)</li>
     *   <li>member 연락처(email/name) 존재 여부 (member.spi)</li>
     * </ul>
     * 불가 시 {@link com.shop.shop.common.exception.PaymentEventResolutionException}(409).
     * 이 검증은 결제 경로 전용이며 상태 조회 경로는 호출하지 않는다(#3).
     *
     * @param orderId          주문 ID
     * @param requesterUserId  요청자 userId
     * @return 결제 준비용 주문 스냅샷
     * @throws com.shop.shop.common.exception.OrderNotFoundException         타인/미존재 주문 (404 존재 은닉)
     * @throws com.shop.shop.common.exception.PaymentEventResolutionException productId/연락처 해석 불가 (409)
     */
    OrderPaymentView getPayableOrder(long orderId, long requesterUserId);

    /**
     * 상태 조회 전용 주문 스냅샷 조회.
     *
     * <p>소유권 검증(404 존재 은닉) + 주문 상태/금액 스냅샷만 반환.
     * productId/연락처 해석을 수행하지 않으므로 409로 깨지지 않는다(#3).
     * 결제 상태 조회·주문 상세 렌더링이 이벤트 payload 구성 실패에 영향받지 않게 한다.
     *
     * @param orderId          주문 ID
     * @param requesterUserId  요청자 userId
     * @return 상태 조회용 주문 스냅샷
     * @throws com.shop.shop.common.exception.OrderNotFoundException 타인/미존재 주문 (404 존재 은닉)
     */
    OrderSnapshotView getOrderSnapshot(long orderId, long requesterUserId);

    /**
     * 취소 전용 locked 주문 스냅샷 조회 (#4).
     *
     * <p><b>orders row PESSIMISTIC_WRITE 잠금</b>: 환불 결정(상태 판정) 전에 호출해
     * 동시 결제({@code OrderConfirmation.confirmPaid}도 같은 row PESSIMISTIC_WRITE)와 직렬화한다.
     * 무락 {@link #getOrderSnapshot} 재사용 금지 — 환불/확정 race 방지(#4).
     *
     * <p>이 락은 PaymentService.cancel의 같은 트랜잭션 전체에 걸쳐 유효하다(④ OrderCancellation 위임까지).
     * OrderCancellationImpl도 같은 트랜잭션에서 findByIdForUpdate를 재호출해 락 재진입/권위 재검증을 수행한다.
     *
     * <p>items는 조회하지 않는다 — 재고 복원에 필요한 items 로딩은 OrderCancellationImpl이 수행.
     *
     * @param orderId          주문 ID
     * @param requesterUserId  요청자 userId
     * @return 취소 판정용 주문 스냅샷 (orders row 락 획득 상태)
     * @throws com.shop.shop.common.exception.OrderNotFoundException 타인/미존재 주문 (404 존재 은닉)
     */
    OrderSnapshotView getOrderForCancel(long orderId, long requesterUserId);

    /**
     * 시스템 만료 전용 locked 주문 스냅샷 조회.
     *
     * <p><b>orders row PESSIMISTIC_WRITE 잠금, 소유권 검증 없음(시스템 주도).</b>
     * 환불 결정 전 락 선점(R3) — 동시 결제({@code confirmPaid})·동시 사용자 취소와 직렬화.
     * 같은 트랜잭션의 {@link OrderCancellation#cancelByExpiry}까지 락 유효(재진입).
     *
     * <p>{@link #getOrderForCancel}(소유권 검증 O)과 대비.
     *
     * @param orderId 주문 ID
     * @return 만료 판정용 주문 스냅샷 (orders row 락 획득 상태, 소유권 없음)
     * @throws com.shop.shop.common.exception.OrderNotFoundException 미존재 주문 (404)
     */
    OrderSnapshotView getOrderForExpiry(long orderId);

    /**
     * 결제 준비용 주문 스냅샷 (Entity 미노출, scalar only).
     *
     * @param orderId     주문 PK
     * @param orderNumber 주문 번호
     * @param userId      소유자 userId (= memberId)
     * @param status      주문 상태 (lowercase)
     * @param finalAmount 주문 최종 금액
     * @param currency    통화 코드 (예: "KRW")
     */
    record OrderPaymentView(
            long orderId,
            String orderNumber,
            long userId,
            String status,
            BigDecimal finalAmount,
            String currency
    ) {}

    /**
     * 상태 조회용 주문 스냅샷 (Entity 미노출, scalar only).
     *
     * <p>OrderPaymentView와 동형이나 의미상 분리한다 — 사용 경로가 다름(#3).
     *
     * @param orderId     주문 PK
     * @param orderNumber 주문 번호
     * @param userId      소유자 userId
     * @param status      주문 상태 (lowercase)
     * @param finalAmount 주문 최종 금액
     * @param currency    통화 코드
     */
    record OrderSnapshotView(
            long orderId,
            String orderNumber,
            long userId,
            String status,
            BigDecimal finalAmount,
            String currency
    ) {}
}
