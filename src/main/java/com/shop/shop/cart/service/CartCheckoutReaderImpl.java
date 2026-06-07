package com.shop.shop.cart.service;

import com.shop.shop.cart.domain.CartItem;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.spi.CartCheckoutReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link CartCheckoutReader} 구현체 (package-private).
 *
 * <p>cart 내부 비공개 {@code service} 패키지에 배치한다.
 * order는 인터페이스({@link CartCheckoutReader})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>CartRepository / CartItemRepository 재사용 (기존 빈 참조)</li>
 *   <li>CartItem Entity → CartCheckoutItem record 변환 (Entity 미노출)</li>
 *   <li>clearCart: cartItemRepository.deleteByCartId 으로 항목 일괄 삭제</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class CartCheckoutReaderImpl implements CartCheckoutReader {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    /**
     * {@inheritDoc}
     *
     * <p>cart가 없거나 항목이 없으면 items=[] 를 반환한다.
     * order가 EmptyCartException 판정을 수행한다.
     */
    @Override
    @Transactional(readOnly = true)
    public CartCheckout getCheckoutCart(long userId) {
        return cartRepository.findByUserId(userId)
                .map(cart -> {
                    List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
                    List<CartCheckoutItem> checkoutItems = items.stream()
                            .map(item -> new CartCheckoutItem(item.getId(), item.getVariantId(), item.getQuantity()))
                            .toList();
                    return new CartCheckout(cart.getId(), checkoutItems);
                })
                .orElse(new CartCheckout(0L, List.of()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>cart가 없는 userId는 비울 항목이 없으므로 삭제 없이 정상 반환한다.
     * (order 트랜잭션 경계 안에서 호출됨)
     */
    @Override
    @Transactional
    public void clearCart(long userId) {
        cartRepository.findByUserId(userId)
                .ifPresent(cart -> cartItemRepository.deleteByCartId(cart.getId()));
    }
}
