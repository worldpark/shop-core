package com.shop.shop.cart.service;

import com.shop.shop.cart.dto.CartItemAddRequest;
import com.shop.shop.cart.dto.CartItemQuantityUpdateRequest;
import com.shop.shop.cart.dto.CartItemResponse;
import com.shop.shop.cart.dto.CartResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CartServiceResponse 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(long) auth.getPrincipal()로 userId 추출 후 CartService 위임</li>
 *   <li>CartView → CartResponse 변환</li>
 *   <li>응답에 stock 수치/ownerId/Entity 미노출</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CartServiceResponseTest {

    @Mock
    private CartService cartService;

    @Mock
    private CartDtoMapper dtoMapper;

    @Mock
    private Authentication authentication;

    private CartServiceResponse cartServiceResponse;

    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        cartServiceResponse = new CartServiceResponse(cartService, dtoMapper);
    }

    @Test
    @DisplayName("getCart — (long)auth.getPrincipal()로 userId 추출 후 CartService.getCart 위임")
    void getCart_extractsUserIdFromPrincipal_delegatesToService() {
        when(authentication.getPrincipal()).thenReturn(USER_ID);
        CartService.CartView cartView = CartService.CartView.empty(1L);
        CartResponse expected = new CartResponse(1L, List.of(), 0, BigDecimal.ZERO, false);
        when(cartService.getCart(USER_ID)).thenReturn(cartView);
        when(dtoMapper.toCartResponse(cartView)).thenReturn(expected);

        CartResponse result = cartServiceResponse.getCart(authentication);

        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cartService).getCart(userIdCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("addItem — userId 추출 후 CartService.addItem 위임")
    void addItem_extractsUserIdAndDelegatesToService() {
        when(authentication.getPrincipal()).thenReturn(USER_ID);
        CartItemAddRequest request = new CartItemAddRequest(10L, 2);
        CartService.CartView cartView = CartService.CartView.empty(1L);
        CartResponse expected = new CartResponse(1L, List.of(), 0, BigDecimal.ZERO, false);
        when(cartService.getCart(USER_ID)).thenReturn(cartView);
        when(dtoMapper.toCartResponse(cartView)).thenReturn(expected);

        CartResponse result = cartServiceResponse.addItem(authentication, request);

        verify(cartService).addItem(USER_ID, 10L, 2);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("updateQuantity — userId 추출 후 CartService.updateItemQuantity 위임")
    void updateQuantity_extractsUserIdAndDelegatesToService() {
        when(authentication.getPrincipal()).thenReturn(USER_ID);
        CartItemQuantityUpdateRequest request = new CartItemQuantityUpdateRequest(5);
        CartService.CartView cartView = CartService.CartView.empty(1L);
        CartResponse expected = new CartResponse(1L, List.of(), 0, BigDecimal.ZERO, false);
        when(cartService.getCart(USER_ID)).thenReturn(cartView);
        when(dtoMapper.toCartResponse(cartView)).thenReturn(expected);

        CartResponse result = cartServiceResponse.updateQuantity(authentication, 100L, request);

        verify(cartService).updateItemQuantity(USER_ID, 100L, 5);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("removeItem — userId 추출 후 CartService.removeItem 위임")
    void removeItem_extractsUserIdAndDelegatesToService() {
        when(authentication.getPrincipal()).thenReturn(USER_ID);
        cartServiceResponse.removeItem(authentication, 200L);

        verify(cartService).removeItem(USER_ID, 200L);
    }

    @Test
    @DisplayName("CartItemResponse 필드에 stock 수치가 없음 (stockEnough boolean만)")
    void cartItemResponse_doesNotContainStockNumber() {
        var fields = Arrays.stream(CartItemResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(fields).doesNotContain("stock");
        assertThat(fields).contains("stockEnough");
    }

    @Test
    @DisplayName("CartItemResponse 필드에 ownerId/storageKey/Entity 없음")
    void cartItemResponse_doesNotContainSensitiveFields() {
        var fields = Arrays.stream(CartItemResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(fields).doesNotContain("ownerId", "storageKey");
    }
}
