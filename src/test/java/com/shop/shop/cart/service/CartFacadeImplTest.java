package com.shop.shop.cart.service;

import com.shop.shop.cart.dto.CartResponse;
import com.shop.shop.member.spi.MemberDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CartFacadeImpl 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>MemberDirectory.findUserIdByEmail(email) → userId 변환 위임</li>
 *   <li>CartService 호출 (변환된 userId 사용)</li>
 *   <li>CartView → CartResponse DTO 변환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CartFacadeImplTest {

    @Mock
    private CartService cartService;

    @Mock
    private MemberDirectory memberDirectory;

    @Mock
    private CartDtoMapper dtoMapper;

    private CartFacadeImpl cartFacadeImpl;

    private static final String EMAIL = "user@example.com";
    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        cartFacadeImpl = new CartFacadeImpl(cartService, memberDirectory, dtoMapper);
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
    }

    @Test
    @DisplayName("getCart — email → userId 변환 후 CartService.getCart 위임")
    void getCart_translatesEmailToUserId_delegatesToService() {
        CartService.CartView cartView = CartService.CartView.empty(1L);
        CartResponse expected = new CartResponse(1L, List.of(), 0, BigDecimal.ZERO, false);
        when(cartService.getCart(USER_ID)).thenReturn(cartView);
        when(dtoMapper.toCartResponse(cartView)).thenReturn(expected);

        CartResponse result = cartFacadeImpl.getCart(EMAIL);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(cartService).getCart(USER_ID);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("addItem — email → userId 변환 후 CartService.addItem 위임")
    void addItem_translatesEmailToUserId_delegatesToService() {
        cartFacadeImpl.addItem(EMAIL, 10L, 2);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(cartService).addItem(USER_ID, 10L, 2);
    }

    @Test
    @DisplayName("updateQuantity — email → userId 변환 후 CartService.updateItemQuantity 위임")
    void updateQuantity_translatesEmailToUserId_delegatesToService() {
        cartFacadeImpl.updateQuantity(EMAIL, 100L, 5);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(cartService).updateItemQuantity(USER_ID, 100L, 5);
    }

    @Test
    @DisplayName("removeItem — email → userId 변환 후 CartService.removeItem 위임")
    void removeItem_translatesEmailToUserId_delegatesToService() {
        cartFacadeImpl.removeItem(EMAIL, 200L);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(cartService).removeItem(USER_ID, 200L);
    }
}
