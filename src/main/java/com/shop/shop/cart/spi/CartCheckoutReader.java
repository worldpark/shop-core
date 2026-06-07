package com.shop.shop.cart.spi;

import java.util.List;

/**
 * 장바구니 체크아웃 전용 published port (cart 소유).
 *
 * <p>order 모듈이 주문 생성 시 사용하는 cart 읽기·비우기 포트.
 * cart Entity를 노출하지 않는다 — record(scalar)만 반환.
 *
 * <p>clearCart 인자에 cartId를 전달하지 않는다.
 * uq_carts_user_id 제약으로 user당 cart가 1개이므로 userId만으로 식별하면 충분하다.
 * cartId 동반 시 불일치 위험만 증가한다 (plan 1.8 clearCart 설계 이유).
 *
 * <p>의존 방향: order → cart.spi 단방향. cart는 order를 참조하지 않는다.
 */
public interface CartCheckoutReader {

    /**
     * 주문 가능 항목 조회.
     *
     * <p>cart가 없거나 항목이 없으면 items=[] 인 CartCheckout을 반환한다.
     * order가 빈 장바구니 판정을 수행한다(EmptyCartException).
     *
     * @param userId 회원 userId
     * @return CartCheckout (cartId, 항목 목록)
     */
    CartCheckout getCheckoutCart(long userId);

    /**
     * 장바구니 비우기.
     *
     * <p>주문 생성 트랜잭션 안에서 호출된다 (order 트랜잭션 경계 안).
     * cart가 없는 userId는 비울 것이 없음 — 조용한 no-op이 아닌 정의된 처리.
     *
     * @param userId 회원 userId
     */
    void clearCart(long userId);

    /**
     * 체크아웃용 장바구니 집계 (cart Entity 미노출, scalar only).
     *
     * @param cartId 장바구니 ID
     * @param items  장바구니 항목 목록
     */
    record CartCheckout(long cartId, List<CartCheckoutItem> items) {}

    /**
     * 체크아웃용 장바구니 항목 (scalar only).
     *
     * @param cartItemId 장바구니 항목 ID
     * @param variantId  variant ID
     * @param quantity   수량
     */
    record CartCheckoutItem(long cartItemId, long variantId, int quantity) {}
}
