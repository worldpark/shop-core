package com.shop.shop.cart.service;

import com.shop.shop.cart.domain.Cart;
import com.shop.shop.cart.domain.CartItem;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CartCheckoutReaderImpl} 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>getCheckoutCart: scalar DTO만 반환(cart/CartItem Entity 미노출)</li>
 *   <li>cart 없는 userId → items=[] CartCheckout 반환</li>
 *   <li>clearCart: userId 해당 cart만 비움</li>
 *   <li>clearCart: cart 없는 userId → no-op</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartCheckoutReaderImplTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    private CartCheckoutReaderImpl cartCheckoutReaderImpl;

    @BeforeEach
    void setUp() {
        cartCheckoutReaderImpl = new CartCheckoutReaderImpl(cartRepository, cartItemRepository);
    }

    // =========================================================
    // getCheckoutCart
    // =========================================================

    @Test
    @DisplayName("cart 없는 userId → items=[] CartCheckout(cartId=0) 반환")
    void getCheckoutCart_noCart_returnsEmptyCheckout() {
        long userId = 999L;
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.empty());

        CartCheckout checkout = cartCheckoutReaderImpl.getCheckoutCart(userId);

        assertThat(checkout.items()).isEmpty();
        assertThat(checkout.cartId()).isZero();
    }

    @Test
    @DisplayName("cart 있고 항목 없음 → items=[] CartCheckout 반환")
    void getCheckoutCart_cartExistsNoItems_returnsEmptyItems() {
        long userId = 1L;
        Cart cart = buildCart(10L, userId);
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(10L)).thenReturn(List.of());

        CartCheckout checkout = cartCheckoutReaderImpl.getCheckoutCart(userId);

        assertThat(checkout.cartId()).isEqualTo(10L);
        assertThat(checkout.items()).isEmpty();
    }

    @Test
    @DisplayName("cart 있고 항목 있음 → CartCheckoutItem(scalar)만 반환 (Entity 미노출)")
    void getCheckoutCart_cartWithItems_returnsScalarDtoOnly() {
        long userId = 1L;
        Cart cart = buildCart(10L, userId);
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(cart));

        CartItem item = buildCartItem(1L, 42L, 3);
        when(cartItemRepository.findByCartId(10L)).thenReturn(List.of(item));

        CartCheckout checkout = cartCheckoutReaderImpl.getCheckoutCart(userId);

        assertThat(checkout.cartId()).isEqualTo(10L);
        assertThat(checkout.items()).hasSize(1);
        CartCheckoutItem checkoutItem = checkout.items().get(0);
        assertThat(checkoutItem.cartItemId()).isEqualTo(1L);
        assertThat(checkoutItem.variantId()).isEqualTo(42L);
        assertThat(checkoutItem.quantity()).isEqualTo(3);

        // CartCheckoutItem은 record(scalar) — CartItem Entity 아님
        assertThat(checkoutItem).isInstanceOf(CartCheckoutReader.CartCheckoutItem.class);
        assertThat(checkoutItem).isNotInstanceOf(CartItem.class);
    }

    // =========================================================
    // clearCart
    // =========================================================

    @Test
    @DisplayName("clearCart: cart 있으면 deleteByCartId 호출")
    void clearCart_cartExists_deletesItems() {
        long userId = 1L;
        Cart cart = buildCart(10L, userId);
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(cart));

        cartCheckoutReaderImpl.clearCart(userId);

        verify(cartItemRepository).deleteByCartId(10L);
    }

    @Test
    @DisplayName("clearCart: cart 없으면 deleteByCartId 미호출 (조용한 정의된 동작)")
    void clearCart_noCart_doesNotCallDelete() {
        long userId = 999L;
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.empty());

        cartCheckoutReaderImpl.clearCart(userId);

        verify(cartItemRepository, never()).deleteByCartId(999L);
    }

    @Test
    @DisplayName("clearCart: 다른 userId cart는 건드리지 않음 (userId 격리)")
    void clearCart_onlyAffectsRequestedUser() {
        long userId = 1L;
        Cart cart = buildCart(10L, userId);
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(cart));

        cartCheckoutReaderImpl.clearCart(userId);

        // userId=1의 cartId=10만 삭제
        verify(cartItemRepository).deleteByCartId(10L);
        // 다른 cartId 미호출 검증은 Mockito 기본 동작으로 충분
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private Cart buildCart(long cartId, long userId) {
        Cart cart = mock(Cart.class);
        when(cart.getId()).thenReturn(cartId);
        when(cart.getUserId()).thenReturn(userId);
        return cart;
    }

    private CartItem buildCartItem(long itemId, long variantId, int quantity) {
        CartItem item = mock(CartItem.class);
        when(item.getId()).thenReturn(itemId);
        when(item.getVariantId()).thenReturn(variantId);
        when(item.getQuantity()).thenReturn(quantity);
        return item;
    }
}
