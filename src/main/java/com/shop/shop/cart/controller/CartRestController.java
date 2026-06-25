package com.shop.shop.cart.controller;

import com.shop.shop.cart.dto.CartItemAddRequest;
import com.shop.shop.cart.dto.CartItemQuantityUpdateRequest;
import com.shop.shop.cart.dto.CartResponse;
import com.shop.shop.cart.service.CartServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장바구니 REST API 컨트롤러.
 *
 * <p>비즈니스 로직 없음 — CartServiceResponse에 전적으로 위임.
 * 모든 엔드포인트는 hasRole("CONSUMER") 이상 인증 필요 (SecurityConfig에 명시).
 * Authentication 주입: JWT 필터 후 principal=userId(long).
 */
@Tag(name = "cart", description = "장바구니 — 조회·담기·수량 변경·삭제 (CONSUMER 이상)")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartRestController {

    private final CartServiceResponse cartServiceResponse;

    /**
     * 내 장바구니 조회.
     *
     * <p>장바구니가 없으면 빈 장바구니를 반환한다 (자동 생성은 첫 담기 시).
     * 빈 조회 시에도 빈 CartResponse(cartId, items=[], ...)를 반환한다.
     *
     * @param authentication JWT principal (userId)
     * @return 200 + CartResponse
     */
    @Operation(summary = "내 장바구니 조회")
    @GetMapping
    public ResponseEntity<CartResponse> getCart(Authentication authentication) {
        CartResponse response = cartServiceResponse.getCart(authentication);
        return ResponseEntity.ok(response);
    }

    /**
     * 장바구니 담기.
     *
     * <p>같은 variant 재담기 시 기존 항목 quantity 증가 (atomic UPDATE).
     * 검증 실패(quantity &lt; 1, variantId null) → 400.
     *
     * @param authentication JWT principal (userId)
     * @param request        담기 요청 (@Valid: variantId NotNull, quantity Min(1))
     * @return 200 + 갱신된 CartResponse
     */
    @Operation(summary = "장바구니 담기")
    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            Authentication authentication,
            @Valid @RequestBody CartItemAddRequest request) {
        CartResponse response = cartServiceResponse.addItem(authentication, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 장바구니 항목 수량 변경 (절대값 set, last-write-wins).
     *
     * <p>타인/미존재 cartItem → 404 존재 은닉.
     *
     * @param authentication JWT principal (userId)
     * @param cartItemId     수량 변경할 항목 ID
     * @param request        수량 변경 요청 (@Valid: quantity Min(1))
     * @return 200 + 갱신된 CartResponse
     */
    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<CartResponse> updateQuantity(
            Authentication authentication,
            @PathVariable long cartItemId,
            @Valid @RequestBody CartItemQuantityUpdateRequest request) {
        CartResponse response = cartServiceResponse.updateQuantity(authentication, cartItemId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 장바구니 항목 삭제.
     *
     * <p>타인/미존재 cartItem → 404 존재 은닉.
     *
     * @param authentication JWT principal (userId)
     * @param cartItemId     삭제할 항목 ID
     * @return 204 No Content
     */
    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> removeItem(
            Authentication authentication,
            @PathVariable long cartItemId) {
        cartServiceResponse.removeItem(authentication, cartItemId);
        return ResponseEntity.noContent().build();
    }
}
