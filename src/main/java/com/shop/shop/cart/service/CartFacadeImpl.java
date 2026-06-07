package com.shop.shop.cart.service;

import com.shop.shop.cart.dto.CartResponse;
import com.shop.shop.cart.spi.CartFacade;
import com.shop.shop.member.spi.MemberDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link CartFacade} 구현체 (package-private).
 *
 * <p>cart 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link CartFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 * (PublicProductFacadeImpl 선례)
 *
 * <p>책임:
 * <ul>
 *   <li>form-login email → userId 변환: {@link MemberDirectory#findUserIdByEmail(String)}</li>
 *   <li>CartService 위임</li>
 *   <li>CartDtoMapper를 통한 CartView → CartResponse DTO 변환</li>
 * </ul>
 *
 * <p>View facade는 email을 받아 내부 userId 변환 후 CartService 호출.
 * REST는 이 facade 미사용 — CartServiceResponse 경유.
 */
@Service
@RequiredArgsConstructor
class CartFacadeImpl implements CartFacade {

    private final CartService cartService;
    private final MemberDirectory memberDirectory;
    private final CartDtoMapper dtoMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public CartResponse getCart(String email) {
        long userId = memberDirectory.findUserIdByEmail(email);
        CartService.CartView cartView = cartService.getCart(userId);
        return dtoMapper.toCartResponse(cartView);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addItem(String email, long variantId, int quantity) {
        long userId = memberDirectory.findUserIdByEmail(email);
        cartService.addItem(userId, variantId, quantity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateQuantity(String email, long cartItemId, int quantity) {
        long userId = memberDirectory.findUserIdByEmail(email);
        cartService.updateItemQuantity(userId, cartItemId, quantity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeItem(String email, long cartItemId) {
        long userId = memberDirectory.findUserIdByEmail(email);
        cartService.removeItem(userId, cartItemId);
    }
}
