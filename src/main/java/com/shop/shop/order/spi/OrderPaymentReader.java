package com.shop.shop.order.spi;

import java.math.BigDecimal;

/**
 * 결제용 주문 스냅샷 조회 published port (order 소유).
 *
 * <p>payment 모듈이 order 내부(domain/repository/service)를 직접 참조하지 않고
 * 이 포트를 통해서만 주문 정보에 접근한다(architecture-rule P1).
 *
 * <p>메서드 2개 분리(#3):
 * <ul>
 *   <li>{@link #getPayableOrder} — 결제(pay) 전용. 이벤트 완결성 사전검증 포함. 409 가능.</li>
 *   <li>{@link #getOrderSnapshot} — 상태 조회 전용. 완결성 검증 없음. 404만 가능.</li>
 * </ul>
 *
 * <p>두 메서드 모두 락을 잡지 않는다(준비/멱등 판정용 읽기).
 * 소유권 검증: 타 사용자 주문·미존재 모두 {@link com.shop.shop.common.exception.OrderNotFoundException}(404 존재 은닉).
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
