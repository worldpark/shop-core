package com.shop.shop.cart.service;

import com.shop.shop.cart.dto.CartItemAddRequest;
import com.shop.shop.cart.dto.CartItemQuantityUpdateRequest;
import com.shop.shop.cart.dto.CartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 장바구니 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — CartService에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다 (Constraint).
 *
 * <p>REST principal: JWT 필터 후 {@code (long) authentication.getPrincipal()} — MemberServiceResponse.me 선례.
 *
 * <p>레이어: CartRestController → CartServiceResponse → CartService → Repository
 */
@Service
@RequiredArgsConstructor
public class CartServiceResponse {

    private final CartService cartService;
    private final CartDtoMapper dtoMapper;

    /**
     * 내 장바구니 조회 — REST 전용.
     *
     * @param authentication JWT SecurityContext
     * @return CartResponse (stock 수치/ownerId/Entity 미노출)
     */
    public CartResponse getCart(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        CartService.CartView cartView = cartService.getCart(userId);
        return dtoMapper.toCartResponse(cartView);
    }

    /**
     * 장바구니 담기 후 갱신된 장바구니 반환 — REST 전용.
     *
     * @param authentication JWT SecurityContext
     * @param request        담기 요청 DTO
     * @return 갱신된 CartResponse
     */
    public CartResponse addItem(Authentication authentication, CartItemAddRequest request) {
        long userId = (long) authentication.getPrincipal();
        cartService.addItem(userId, request.variantId(), request.quantity());
        CartService.CartView cartView = cartService.getCart(userId);
        return dtoMapper.toCartResponse(cartView);
    }

    /**
     * 장바구니 수량 변경 후 갱신된 장바구니 반환 — REST 전용.
     *
     * @param authentication JWT SecurityContext
     * @param cartItemId     수량 변경할 항목 ID
     * @param request        수량 변경 요청 DTO
     * @return 갱신된 CartResponse
     */
    public CartResponse updateQuantity(Authentication authentication, long cartItemId,
                                       CartItemQuantityUpdateRequest request) {
        long userId = (long) authentication.getPrincipal();
        cartService.updateItemQuantity(userId, cartItemId, request.quantity());
        CartService.CartView cartView = cartService.getCart(userId);
        return dtoMapper.toCartResponse(cartView);
    }

    /**
     * 장바구니 항목 삭제 — REST 전용.
     *
     * @param authentication JWT SecurityContext
     * @param cartItemId     삭제할 항목 ID
     */
    public void removeItem(Authentication authentication, long cartItemId) {
        long userId = (long) authentication.getPrincipal();
        cartService.removeItem(userId, cartItemId);
    }
}
