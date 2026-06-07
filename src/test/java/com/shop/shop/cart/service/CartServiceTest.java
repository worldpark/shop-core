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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CartService 단위 테스트 (Mockito).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>장바구니 최초 조회 시 생성(getOrCreateCart)</li>
 *   <li>담기 성공(신규 insert)</li>
 *   <li>같은 variant 재담기 시 increaseQuantityWithinStock(atomic UPDATE) 호출</li>
 *   <li>quantity 0/음수 → 400</li>
 *   <li>비purchasable variant 담기 → 400</li>
 *   <li>신규 담기 요청 quantity>stock → 400</li>
 *   <li>재담기 atomic UPDATE affected 0 → 400</li>
 *   <li>수량 변경 성공(changeQuantity 절대값)</li>
 *   <li>수량 변경 시 비관적 락 미사용(findById는 일반 조회)</li>
 *   <li>수량 변경 절대값 stock 초과 → 400</li>
 *   <li>항목 삭제 성공</li>
 *   <li>타인 cartItem 수량변경/삭제 → 404</li>
 *   <li>조회 시 ProductPurchaseCatalog IN 배치 1회 호출</li>
 *   <li>available/stockEnough/unitPrice/lineAmount/total 조립</li>
 *   <li>totalAmount = 구매가능 항목만 합산</li>
 *   <li>재고 차감 호출 없음</li>
 *   <li>생성 경합 복구(DataIntegrityViolation → 재조회)</li>
 *   <li>첫 동시 담기 경합 복구(insert DataIntegrityViolation → 재조회 + atomic 증가)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private ProductPurchaseCatalog productPurchaseCatalog;

    @InjectMocks
    private CartService cartService;

    private static final long USER_ID = 1L;
    private static final long VARIANT_ID = 10L;
    private static final long CART_ID = 100L;
    private static final long CART_ITEM_ID = 1000L;

    private Cart mockCart;
    private CartItem mockCartItem;

    @BeforeEach
    void setUp() {
        mockCart = Cart.create(USER_ID);
        setField(mockCart, "id", CART_ID);

        mockCartItem = CartItem.create(mockCart, VARIANT_ID, 2);
        setField(mockCartItem, "id", CART_ITEM_ID);
    }

    // =============================================================
    // getOrCreateCart
    // =============================================================

    @Test
    @DisplayName("장바구니 최초 조회 시 새로 생성하고 반환한다")
    void getOrCreateCart_newCart_savesAndReturns() {
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenReturn(mockCart);

        Cart result = cartService.getOrCreateCart(USER_ID);

        assertThat(result).isEqualTo(mockCart);
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    @DisplayName("장바구니가 이미 있으면 기존 장바구니를 반환한다")
    void getOrCreateCart_existingCart_returnsExisting() {
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));

        Cart result = cartService.getOrCreateCart(USER_ID);

        assertThat(result).isEqualTo(mockCart);
        verify(cartRepository, never()).save(any());
    }

    @Test
    @DisplayName("생성 경합(DataIntegrityViolation) → findByUserId 재조회로 복구된다")
    void getOrCreateCart_uniqueConflict_recoversWithReQuery() {
        when(cartRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mockCart));
        when(cartRepository.save(any(Cart.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        Cart result = cartService.getOrCreateCart(USER_ID);

        assertThat(result).isEqualTo(mockCart);
    }

    // =============================================================
    // addItem
    // =============================================================

    @Test
    @DisplayName("담기 성공 — 신규 항목 insert 호출")
    void addItem_newItem_savesCartItem() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 10);
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartIdAndVariantId(CART_ID, VARIANT_ID))
                .thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.addItem(USER_ID, VARIANT_ID, 2);

        verify(cartItemRepository).save(any(CartItem.class));
        // read-modify-write 경로 미호출 단언
        verify(cartItemRepository, never()).increaseQuantityWithinStock(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("같은 variant 재담기 시 increaseQuantityWithinStock(atomic UPDATE) 호출 — read-modify-write 아님")
    void addItem_existingItem_callsAtomicIncreaseUpdate() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 10);
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartIdAndVariantId(CART_ID, VARIANT_ID))
                .thenReturn(Optional.of(mockCartItem));
        when(cartItemRepository.increaseQuantityWithinStock(CART_ID, VARIANT_ID, 3, 10)).thenReturn(1);

        cartService.addItem(USER_ID, VARIANT_ID, 3);

        verify(cartItemRepository).increaseQuantityWithinStock(CART_ID, VARIANT_ID, 3, 10);
        // 절대값 set 경로 미호출 단언
        verify(cartItemRepository, never()).save(any(CartItem.class));
    }

    @Test
    @DisplayName("quantity=0 → 400")
    void addItem_quantityZero_throws400() {
        assertThatThrownBy(() -> cartService.addItem(USER_ID, VARIANT_ID, 0))
                .isInstanceOf(VariantNotPurchasableException.class);
    }

    @Test
    @DisplayName("quantity=-1 → 400")
    void addItem_quantityNegative_throws400() {
        assertThatThrownBy(() -> cartService.addItem(USER_ID, VARIANT_ID, -1))
                .isInstanceOf(VariantNotPurchasableException.class);
    }

    @Test
    @DisplayName("비purchasable variant 담기 → 400")
    void addItem_notPurchasable_throws400() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, false, 10);
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);

        assertThatThrownBy(() -> cartService.addItem(USER_ID, VARIANT_ID, 1))
                .isInstanceOf(VariantNotPurchasableException.class);
    }

    @Test
    @DisplayName("신규 담기 요청 quantity > stock → 400")
    void addItem_quantityExceedsStock_throws400() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 3);
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);

        assertThatThrownBy(() -> cartService.addItem(USER_ID, VARIANT_ID, 5))
                .isInstanceOf(CartItemStockExceededException.class);
    }

    @Test
    @DisplayName("재담기 atomic UPDATE affected 0(합산 stock 초과) → 400, 증가분 유실 없음")
    void addItem_readdAtomicUpdateAffectedZero_throws400() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 5);
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartIdAndVariantId(CART_ID, VARIANT_ID))
                .thenReturn(Optional.of(mockCartItem));
        when(cartItemRepository.increaseQuantityWithinStock(CART_ID, VARIANT_ID, 4, 5)).thenReturn(0);

        assertThatThrownBy(() -> cartService.addItem(USER_ID, VARIANT_ID, 4))
                .isInstanceOf(CartItemStockExceededException.class);

        // 사후 보정 저장 없음 단언 (증가분 유실 없음)
        verify(cartItemRepository, never()).save(any(CartItem.class));
    }

    @Test
    @DisplayName("첫 동시 담기 경합(insert DataIntegrityViolation) → 재조회 후 atomic 증가")
    void addItem_insertConflict_recoversWithAtomicIncrease() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 10);
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartIdAndVariantId(CART_ID, VARIANT_ID))
                .thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(cartItemRepository.increaseQuantityWithinStock(CART_ID, VARIANT_ID, 2, 10)).thenReturn(1);

        cartService.addItem(USER_ID, VARIANT_ID, 2);

        verify(cartItemRepository).increaseQuantityWithinStock(CART_ID, VARIANT_ID, 2, 10);
    }

    // =============================================================
    // updateItemQuantity
    // =============================================================

    @Test
    @DisplayName("수량 변경 성공 — changeQuantity 절대값 set 호출")
    void updateItemQuantity_success_callsChangeQuantity() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 10);
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.of(mockCartItem));
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);

        cartService.updateItemQuantity(USER_ID, CART_ITEM_ID, 5);

        assertThat(mockCartItem.getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("수량 변경 시 findById는 일반 조회(비관적 락 미사용)")
    void updateItemQuantity_usesNonLockingFindById() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 10);
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.of(mockCartItem));
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);

        cartService.updateItemQuantity(USER_ID, CART_ITEM_ID, 3);

        // findById(비관적 락 없는 일반 조회)만 호출됨 단언
        verify(cartItemRepository).findById(CART_ITEM_ID);
    }

    @Test
    @DisplayName("수량 변경 절대값 stock 초과 → 400")
    void updateItemQuantity_quantityExceedsStock_throws400() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 5);
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.of(mockCartItem));
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);

        assertThatThrownBy(() -> cartService.updateItemQuantity(USER_ID, CART_ITEM_ID, 10))
                .isInstanceOf(CartItemStockExceededException.class);
    }

    @Test
    @DisplayName("타인 cartItem 수량 변경 → 404 존재 은닉")
    void updateItemQuantity_otherUserItem_throws404() {
        long otherUserId = 99L;
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.of(mockCartItem));

        assertThatThrownBy(() -> cartService.updateItemQuantity(otherUserId, CART_ITEM_ID, 1))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    @DisplayName("미존재 cartItem 수량 변경 → 404 존재 은닉")
    void updateItemQuantity_nonExistingItem_throws404() {
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.updateItemQuantity(USER_ID, CART_ITEM_ID, 1))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    // =============================================================
    // removeItem
    // =============================================================

    @Test
    @DisplayName("항목 삭제 성공 — delete 호출")
    void removeItem_success_callsDelete() {
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.of(mockCartItem));

        cartService.removeItem(USER_ID, CART_ITEM_ID);

        verify(cartItemRepository).delete(mockCartItem);
    }

    @Test
    @DisplayName("타인 cartItem 삭제 → 404 존재 은닉")
    void removeItem_otherUserItem_throws404() {
        long otherUserId = 99L;
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.of(mockCartItem));

        assertThatThrownBy(() -> cartService.removeItem(otherUserId, CART_ITEM_ID))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    @DisplayName("미존재 cartItem 삭제 → 404 존재 은닉")
    void removeItem_nonExistingItem_throws404() {
        when(cartItemRepository.findById(CART_ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem(USER_ID, CART_ITEM_ID))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    // =============================================================
    // getCart (조회)
    // =============================================================

    @Test
    @DisplayName("조회 시 ProductPurchaseCatalog.getPurchasableVariants IN 배치 1회 호출")
    void getCart_callsPurchasableCatalogInBatch() {
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(mockCartItem));
        when(productPurchaseCatalog.getPurchasableVariants(List.of(VARIANT_ID)))
                .thenReturn(List.of(purchasableVariant(VARIANT_ID, true, 10)));

        cartService.getCart(USER_ID);

        verify(productPurchaseCatalog).getPurchasableVariants(List.of(VARIANT_ID));
        // 단건 조회 미호출 단언(N+1 회피)
        verify(productPurchaseCatalog, never()).getPurchasableVariant(anyLong());
    }

    @Test
    @DisplayName("조회 시 available/stockEnough/unitPrice/lineAmount/total 조립")
    void getCart_assemblesCartView() {
        CartItem item1 = CartItem.create(mockCart, VARIANT_ID, 2);
        setField(item1, "id", 1L);
        CartItem item2 = CartItem.create(mockCart, 20L, 3);
        setField(item2, "id", 2L);

        PurchasableVariant v1 = purchasableVariant(VARIANT_ID, true, 10); // price=1000, stock=10
        PurchasableVariant v2 = new PurchasableVariant(20L, 2L, "상품B", "ON_SALE", "M", null,
                new BigDecimal("2000"), true, 2, true); // stock=2, quantity=3 → stockEnough=false

        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(item1, item2));
        when(productPurchaseCatalog.getPurchasableVariants(any())).thenReturn(List.of(v1, v2));

        CartService.CartView cartView = cartService.getCart(USER_ID);

        assertThat(cartView.items()).hasSize(2);
        assertThat(cartView.hasUnavailableItem()).isTrue(); // v2 stockEnough=false

        // v1: available=true, stockEnough=true(2<=10), unitPrice=1000, lineAmount=2000
        CartService.CartItemView view1 = cartView.items().stream()
                .filter(v -> v.variantId() == VARIANT_ID).findFirst().orElseThrow();
        assertThat(view1.available()).isTrue();
        assertThat(view1.stockEnough()).isTrue();
        assertThat(view1.unitPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(view1.lineAmount()).isEqualByComparingTo(new BigDecimal("2000"));

        // v2: available=true, stockEnough=false(3>2)
        CartService.CartItemView view2 = cartView.items().stream()
                .filter(v -> v.variantId() == 20L).findFirst().orElseThrow();
        assertThat(view2.stockEnough()).isFalse();

        // totalAmount = 구매가능(available && stockEnough) 항목만 합산 = 2000 (v1만)
        assertThat(cartView.totalAmount()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(cartView.totalQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("unavailable item(purchasable=false) → available=false, hasUnavailableItem=true")
    void getCart_unavailableItem_markedAvailableFalse() {
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(mockCartItem));
        when(productPurchaseCatalog.getPurchasableVariants(List.of(VARIANT_ID)))
                .thenReturn(List.of(purchasableVariant(VARIANT_ID, false, 10)));

        CartService.CartView cartView = cartService.getCart(USER_ID);

        assertThat(cartView.items().get(0).available()).isFalse();
        assertThat(cartView.hasUnavailableItem()).isTrue();
    }

    @Test
    @DisplayName("누락 variantId(삭제됨) → available=false 폴백, cart_item 보존")
    void getCart_deletedVariant_availableFalseAndItemPreserved() {
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(mockCartItem));
        when(productPurchaseCatalog.getPurchasableVariants(List.of(VARIANT_ID)))
                .thenReturn(List.of()); // variant 삭제됨 — 빈 목록 반환

        CartService.CartView cartView = cartService.getCart(USER_ID);

        assertThat(cartView.items()).hasSize(1); // cart_item 보존(자동삭제 금지)
        assertThat(cartView.items().get(0).available()).isFalse();
        verify(cartItemRepository, never()).delete(any(CartItem.class));
    }

    @Test
    @DisplayName("재고 차감 호출 없음 — variant/inventory stock 변경 mock 미호출")
    void addItem_doesNotModifyStock() {
        PurchasableVariant variant = purchasableVariant(VARIANT_ID, true, 10);
        when(productPurchaseCatalog.getPurchasableVariant(VARIANT_ID)).thenReturn(variant);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mockCart));
        when(cartItemRepository.findByCartIdAndVariantId(CART_ID, VARIANT_ID))
                .thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.addItem(USER_ID, VARIANT_ID, 2);

        // ProductPurchaseCatalog가 쓰기 메서드를 갖지 않으므로 stock 변경 호출 없음
        // getPurchasableVariant(조회)만 호출됨 단언
        verify(productPurchaseCatalog).getPurchasableVariant(VARIANT_ID);
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private PurchasableVariant purchasableVariant(long variantId, boolean purchasable, int stock) {
        return new PurchasableVariant(
                variantId, 1L, "테스트 상품", "ON_SALE", "빨강", null,
                new BigDecimal("1000"), purchasable, stock, purchasable
        );
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("필드 설정 실패: " + fieldName, e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }
}
