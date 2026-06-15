package com.shop.shop.product.spi;

/**
 * 실구매 검증 read 포트 (product 소유, @NamedInterface("spi")).
 *
 * <p>order_item 소유·주문 상태·variant→product 조회를 추상화한 포트.
 * product 모듈이 인터페이스를 소유(소비)하고 order/adapter/OrderPurchaseVerificationAdapter가 구현한다.
 *
 * <p>의존 방향: order → product.spi 단방향(기존 ProductOrderCatalog/ProductPurchaseCatalog와 동일 패턴).
 * product는 order를 전혀 참조하지 않는다.
 *
 * <p>반환 결과는 record({@link PurchaseVerification})로 신호 — order adapter가 product 예외를 모름(결합 최소화).
 * product 서비스가 record를 보고 예외(404/400)를 던진다.
 *
 * <p>IDOR 보장: ownedAndExists=false는 미존재와 타인 소유를 구분하지 않는다(존재 은닉).
 */
public interface PurchaseVerificationPort {

    /**
     * 주문 항목 소유·상태·variant→product 통합 검증.
     *
     * <p>검증 순서:
     * <ol>
     *   <li>order_item 존재 + order.userId == userId → ownedAndExists=true(미존재/타인=false)</li>
     *   <li>order.status == "delivered" → delivered=true</li>
     *   <li>order_item.variantId null → productId=null. non-null이면 ProductOrderCatalog로 해석</li>
     * </ol>
     *
     * @param orderItemId 검증할 주문 항목 ID
     * @param userId      인증 사용자 userId
     * @return 검증 결과 record
     */
    PurchaseVerification verify(long orderItemId, long userId);

    /**
     * 실구매 검증 결과 record.
     *
     * @param ownedAndExists 주문 항목이 존재하고 요청자 소유인지 (false = 미존재 또는 타인 소유)
     * @param delivered      주문 상태가 delivered인지
     * @param productId      order_item에서 도출한 상품 ID (null = variant 삭제로 도출 불가)
     */
    record PurchaseVerification(boolean ownedAndExists, boolean delivered, Long productId) {}
}
