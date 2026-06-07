package com.shop.shop.cart.spi;

import com.shop.shop.cart.dto.CartResponse;

/**
 * 장바구니 View 전용 facade (published port).
 *
 * <p>web 모듈의 CartViewController가 cart 도메인 내부 Service·Entity·enum을 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 cart 내부 {@code service} 패키지에 위치한다.
 *
 * <p>email을 인자로 받아 내부에서 {@code member.spi.MemberDirectory}로 userId로 변환한다.
 * REST는 이 facade 미사용 — CartServiceResponse 경유.
 *
 * <p>의존 방향: web → cart.spi 단방향. cart는 web을 참조하지 않는다.
 */
public interface CartFacade {

    /**
     * 내 장바구니 조회.
     *
     * @param email form-login principal email
     * @return CartResponse (stock 수치/ownerId/Entity 미노출)
     */
    CartResponse getCart(String email);

    /**
     * 장바구니 담기.
     *
     * @param email     form-login principal email
     * @param variantId 담을 variant ID
     * @param quantity  수량 (≥ 1)
     */
    void addItem(String email, long variantId, int quantity);

    /**
     * 장바구니 항목 수량 변경 (절대값, last-write-wins).
     *
     * @param email      form-login principal email
     * @param cartItemId 수량 변경할 항목 ID
     * @param quantity   변경 후 수량 절대값 (≥ 1)
     */
    void updateQuantity(String email, long cartItemId, int quantity);

    /**
     * 장바구니 항목 삭제.
     *
     * @param email      form-login principal email
     * @param cartItemId 삭제할 항목 ID
     */
    void removeItem(String email, long cartItemId);
}
