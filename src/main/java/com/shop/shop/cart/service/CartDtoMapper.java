package com.shop.shop.cart.service;

import com.shop.shop.cart.dto.CartItemResponse;
import com.shop.shop.cart.dto.CartResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CartView → CartResponse/CartItemResponse DTO 변환 매퍼 (package-private).
 *
 * <p>CartServiceResponse 및 CartFacadeImpl이 공유하는 DTO 변환 로직을 단일 책임 컴포넌트로 추출해 중복 제거.
 * (PublicProductDtoMapper 선례)
 *
 * <p>stock 수치/ownerId/Entity/storageKey 미노출 변환 규칙은 이 컴포넌트 한 곳에서만 적용한다.
 * cart 모듈 밖(web 등)에서 직접 참조하지 않는다 (package-private).
 */
@Component
class CartDtoMapper {

    /**
     * CartView → CartResponse 변환.
     *
     * <p>stock 수치는 CartItemResponse에 포함하지 않는다(stockEnough boolean만 노출).
     */
    CartResponse toCartResponse(CartService.CartView cartView) {
        List<CartItemResponse> itemResponses = cartView.items().stream()
                .map(this::toCartItemResponse)
                .toList();

        return new CartResponse(
                cartView.cartId(),
                itemResponses,
                cartView.totalQuantity(),
                cartView.totalAmount(),
                cartView.hasUnavailableItem()
        );
    }

    /**
     * CartItemView → CartItemResponse 변환.
     *
     * <p>stock 수치/ownerId/Entity/storageKey 미노출.
     */
    CartItemResponse toCartItemResponse(CartService.CartItemView itemView) {
        return new CartItemResponse(
                itemView.cartItemId(),
                itemView.variantId(),
                itemView.productId(),
                itemView.productName(),
                itemView.optionLabel(),
                itemView.imageUrl(),
                itemView.unitPrice(),
                itemView.quantity(),
                itemView.lineAmount(),
                itemView.available(),
                itemView.stockEnough()
        );
    }
}
