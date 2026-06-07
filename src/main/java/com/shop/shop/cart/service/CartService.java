package com.shop.shop.cart.service;

import com.shop.shop.cart.domain.Cart;
import com.shop.shop.cart.domain.CartItem;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.CartItemNotFoundException;
import com.shop.shop.common.exception.CartItemStockExceededException;
import com.shop.shop.common.exception.VariantNotPurchasableException;
import com.shop.shop.product.spi.ProductPurchaseCatalog;
import com.shop.shop.product.spi.ProductPurchaseCatalog.PurchasableVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장바구니 도메인 서비스.
 *
 * <p>모든 메서드 첫 인자 long userId — principal 이중경로(REST=userId, View=email→userId) 통일.
 * Entity→DTO 변환 없음 — ServiceResponse/FacadeImpl이 담당.
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>재고 차감 금지 — 검증만(variant/inventory stock 변경 절대 불가)</li>
 *   <li>타인/미존재 cartItem = 404 존재 은닉({@link CartItemNotFoundException})</li>
 *   <li>재담기 증가 = stock 검증 포함 atomic UPDATE(read-modify-write 금지)</li>
 *   <li>수량 변경 = last-write-wins, 비관적 락 없음</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductPurchaseCatalog productPurchaseCatalog;

    /**
     * 장바구니 조회 (없으면 생성).
     *
     * <p>생성 경합(uq_carts_user_id): findByUserId 없으면 insert 시도 →
     * DataIntegrityViolationException catch → findByUserId 재조회 복구.
     * 요청 실패 노출 없음 (MemberService.signup unique 복구 선례).
     *
     * @param userId 회원 userId
     * @return 장바구니 (신규 생성 또는 기존 조회)
     */
    public Cart getOrCreateCart(long userId) {
        return cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    try {
                        return cartRepository.save(Cart.create(userId));
                    } catch (DataIntegrityViolationException e) {
                        // 동시 최초 생성 경합 — unique 제약 위반 → 재조회 복구
                        return cartRepository.findByUserId(userId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "cart 생성 경합 복구 실패: userId=" + userId, e));
                    }
                });
    }

    /**
     * 장바구니 + 항목 조회 → 현재 product/variant 상태 합성.
     *
     * <p>getOrCreateCart 후 항목 로드 → variantId 목록으로 ProductPurchaseCatalog.getPurchasableVariants
     * IN 배치 1회 → available/stockEnough/unitPrice/lineAmount/total 집계.
     * 자동 삭제 없음 — unavailable 항목도 cart_item 보존.
     *
     * @param userId 회원 userId
     * @return CartView (내부 집계 결과)
     */
    @Transactional(readOnly = true)
    public CartView getCart(long userId) {
        Cart cart = cartRepository.findByUserId(userId).orElse(null);
        if (cart == null) {
            // 아직 영속된 장바구니 없음 (첫 방문) — 빈 장바구니. 실제 생성은 첫 담기(getOrCreateCart) 시.
            return CartView.empty(0L);
        }

        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) {
            return CartView.empty(cart.getId());
        }

        List<Long> variantIds = items.stream()
                .map(CartItem::getVariantId)
                .toList();

        List<PurchasableVariant> purchasableVariants =
                productPurchaseCatalog.getPurchasableVariants(variantIds);

        Map<Long, PurchasableVariant> variantMap = purchasableVariants.stream()
                .collect(Collectors.toMap(PurchasableVariant::variantId, Function.identity()));

        return CartView.of(cart.getId(), items, variantMap);
    }

    /**
     * 장바구니 담기.
     *
     * <p>담기 흐름:
     * <ol>
     *   <li>quantity &lt; 1 → 400</li>
     *   <li>ProductPurchaseCatalog.getPurchasableVariant(variantId) → purchasable=false/미존재 → 400</li>
     *   <li>신규 담기 stock 검증: quantity &gt; stock → 400</li>
     *   <li>cart 확보 (getOrCreateCart)</li>
     *   <li>기존 항목 있으면 atomic 증가 UPDATE; affected 0 → 400</li>
     *   <li>없으면 신규 insert → unique 경합 시 catch 후 재조회+atomic 증가</li>
     * </ol>
     *
     * @param userId    회원 userId
     * @param variantId 담을 variant ID
     * @param quantity  수량 (≥ 1)
     * @throws VariantNotPurchasableException    비purchasable/미존재 variant
     * @throws CartItemStockExceededException    신규/재담기 stock 초과
     */
    public void addItem(long userId, long variantId, int quantity) {
        if (quantity < 1) {
            throw new VariantNotPurchasableException("수량은 1 이상이어야 합니다.");
        }

        PurchasableVariant variant = productPurchaseCatalog.getPurchasableVariant(variantId);
        if (!variant.purchasable()) {
            throw new VariantNotPurchasableException();
        }

        // 신규 담기 stock 검증
        if (quantity > variant.stock()) {
            throw new CartItemStockExceededException();
        }

        Cart cart = getOrCreateCart(userId);
        Optional<CartItem> existingItem = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variantId);

        if (existingItem.isPresent()) {
            // 재담기: atomic 증가 UPDATE (read-modify-write 금지)
            int affected = cartItemRepository.increaseQuantityWithinStock(
                    cart.getId(), variantId, quantity, variant.stock());
            if (affected == 0) {
                throw new CartItemStockExceededException();
            }
        } else {
            // 신규 insert (첫 동시 담기 경합 처리)
            try {
                cartItemRepository.save(CartItem.create(cart, variantId, quantity));
            } catch (DataIntegrityViolationException e) {
                // unique 경합(uq_cart_items_cart_variant) → 재조회 후 atomic 증가
                int affected = cartItemRepository.increaseQuantityWithinStock(
                        cart.getId(), variantId, quantity, variant.stock());
                if (affected == 0) {
                    throw new CartItemStockExceededException();
                }
            }
        }
    }

    /**
     * 장바구니 항목 수량 변경 (절대값 set, last-write-wins).
     *
     * <p>비관적 락 없음 — findById는 일반 조회. 동시 수정은 last-write-wins 허용.
     *
     * @param userId     회원 userId
     * @param cartItemId 수량 변경할 항목 ID
     * @param quantity   변경 후 수량 절대값 (≥ 1)
     * @throws CartItemNotFoundException      타인/미존재 cartItem (404 존재 은닉)
     * @throws VariantNotPurchasableException 비purchasable variant
     * @throws CartItemStockExceededException 변경 후 절대값 stock 초과
     */
    public void updateItemQuantity(long userId, long cartItemId, int quantity) {
        if (quantity < 1) {
            throw new CartItemStockExceededException("수량은 1 이상이어야 합니다.");
        }

        CartItem item = findOwnedItem(userId, cartItemId);

        PurchasableVariant variant = productPurchaseCatalog.getPurchasableVariant(item.getVariantId());
        if (!variant.purchasable()) {
            throw new VariantNotPurchasableException();
        }

        // 변경 후 절대값 stock 검증
        if (quantity > variant.stock()) {
            throw new CartItemStockExceededException();
        }

        item.changeQuantity(quantity);
    }

    /**
     * 장바구니 항목 삭제.
     *
     * @param userId     회원 userId
     * @param cartItemId 삭제할 항목 ID
     * @throws CartItemNotFoundException 타인/미존재 cartItem (404 존재 은닉)
     */
    public void removeItem(long userId, long cartItemId) {
        CartItem item = findOwnedItem(userId, cartItemId);
        cartItemRepository.delete(item);
    }

    /**
     * 소유권 검증 후 CartItem 반환.
     *
     * <p>findById 없거나 cart.userId != userId → {@link CartItemNotFoundException}(404 존재 은닉).
     * 타인 소유와 미존재를 동일 예외/동일 메시지로 처리해 존재 구분 불가하게 한다. 403 미사용.
     * cart_item.cart는 LAZY이지만 동일 트랜잭션 내 getUserId 접근 가능.
     */
    private CartItem findOwnedItem(long userId, long cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(CartItemNotFoundException::new);

        if (!item.getCart().getUserId().equals(userId)) {
            throw new CartItemNotFoundException();
        }

        return item;
    }

    // =============================================================
    // CartView — 조회 시 집계 결과 (내부 타입)
    // =============================================================

    /**
     * 장바구니 조회 집계 결과 (Entity 미노출, 내부 타입).
     *
     * <p>ServiceResponse·FacadeImpl이 이 타입을 DTO로 변환한다.
     * totalAmount: 구매가능(available && stockEnough) 항목만 합산(주문 가능 금액 기준).
     */
    public record CartView(
            long cartId,
            List<CartItemView> items,
            int totalQuantity,
            BigDecimal totalAmount,
            boolean hasUnavailableItem
    ) {

        static CartView empty(long cartId) {
            return new CartView(cartId, List.of(), 0, BigDecimal.ZERO, false);
        }

        static CartView of(long cartId, List<CartItem> items, Map<Long, PurchasableVariant> variantMap) {
            List<CartItemView> itemViews = items.stream()
                    .map(item -> CartItemView.of(item, variantMap.get(item.getVariantId())))
                    .toList();

            int totalQuantity = itemViews.stream()
                    .mapToInt(CartItemView::quantity)
                    .sum();

            BigDecimal totalAmount = itemViews.stream()
                    .filter(v -> v.available() && v.stockEnough())
                    .map(CartItemView::lineAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            boolean hasUnavailableItem = itemViews.stream()
                    .anyMatch(v -> !v.available() || !v.stockEnough());

            return new CartView(cartId, itemViews, totalQuantity, totalAmount, hasUnavailableItem);
        }
    }

    /**
     * 장바구니 항목 조회 결과 (내부 타입).
     */
    public record CartItemView(
            long cartItemId,
            long variantId,
            long productId,
            String productName,
            String optionLabel,
            String imageUrl,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineAmount,
            boolean available,
            boolean stockEnough
    ) {

        static CartItemView of(CartItem item, PurchasableVariant variant) {
            if (variant == null) {
                // variant 물리 삭제됨 — available=false 폴백, cart_item 보존
                return new CartItemView(
                        item.getId(),
                        item.getVariantId(),
                        0L,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        item.getQuantity(),
                        BigDecimal.ZERO,
                        false,
                        false
                );
            }

            boolean available = variant.purchasable();
            boolean stockEnough = item.getQuantity() <= variant.stock();
            BigDecimal unitPrice = variant.price();
            BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));

            return new CartItemView(
                    item.getId(),
                    item.getVariantId(),
                    variant.productId(),
                    variant.productName(),
                    variant.optionLabel(),
                    variant.imageUrl(),
                    unitPrice,
                    item.getQuantity(),
                    lineAmount,
                    available,
                    stockEnough
            );
        }
    }
}
