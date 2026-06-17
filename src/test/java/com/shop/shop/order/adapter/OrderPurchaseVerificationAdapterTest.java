package com.shop.shop.order.adapter;

import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.PurchaseVerificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * OrderPurchaseVerificationAdapter 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>order_item 미존재 → ownedAndExists=false</li>
 *   <li>타인 소유 order_item → ownedAndExists=false (IDOR 보장)</li>
 *   <li>delivered 상태 → delivered=true</li>
 *   <li>비delivered 상태(pending) → delivered=false</li>
 *   <li>variantId null → productId=null</li>
 *   <li>ProductOrderCatalog로 productId 해석</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderPurchaseVerificationAdapterTest {

    @Mock
    private OrderItemQueryRepository orderItemQueryRepository;
    @Mock
    private ProductOrderCatalog productOrderCatalog;

    private OrderPurchaseVerificationAdapter adapter;

    private static final long ORDER_ITEM_ID = 100L;
    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 99L;
    private static final long VARIANT_ID = 500L;
    private static final long PRODUCT_ID = 200L;

    @BeforeEach
    void setUp() {
        adapter = new OrderPurchaseVerificationAdapter(orderItemQueryRepository, productOrderCatalog);
    }

    @Test
    @DisplayName("order_item 미존재 → ownedAndExists=false")
    void verify_notFound_ownedAndExistsFalse() {
        when(orderItemQueryRepository.findOrderItemProjection(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        PurchaseVerificationPort.PurchaseVerification result = adapter.verify(ORDER_ITEM_ID, USER_ID);

        assertThat(result.ownedAndExists()).isFalse();
    }

    @Test
    @DisplayName("타인 소유 → ownedAndExists=false (IDOR 존재 은닉)")
    void verify_otherUser_ownedAndExistsFalse() {
        when(orderItemQueryRepository.findOrderItemProjection(ORDER_ITEM_ID))
                .thenReturn(Optional.of(new OrderItemQueryRepository.OrderItemProjection(OTHER_USER_ID, "delivered", VARIANT_ID)));

        PurchaseVerificationPort.PurchaseVerification result = adapter.verify(ORDER_ITEM_ID, USER_ID);

        assertThat(result.ownedAndExists()).isFalse();
    }

    @Test
    @DisplayName("delivered 상태 → delivered=true")
    void verify_delivered_deliveredTrue() {
        when(orderItemQueryRepository.findOrderItemProjection(ORDER_ITEM_ID))
                .thenReturn(Optional.of(new OrderItemQueryRepository.OrderItemProjection(USER_ID, "delivered", VARIANT_ID)));
        when(productOrderCatalog.getOrderableSnapshots(List.of(VARIANT_ID)))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID)));

        PurchaseVerificationPort.PurchaseVerification result = adapter.verify(ORDER_ITEM_ID, USER_ID);

        assertThat(result.ownedAndExists()).isTrue();
        assertThat(result.delivered()).isTrue();
    }

    @Test
    @DisplayName("pending 상태 → delivered=false")
    void verify_pending_deliveredFalse() {
        when(orderItemQueryRepository.findOrderItemProjection(ORDER_ITEM_ID))
                .thenReturn(Optional.of(new OrderItemQueryRepository.OrderItemProjection(USER_ID, "pending", VARIANT_ID)));
        when(productOrderCatalog.getOrderableSnapshots(List.of(VARIANT_ID)))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID)));

        PurchaseVerificationPort.PurchaseVerification result = adapter.verify(ORDER_ITEM_ID, USER_ID);

        assertThat(result.ownedAndExists()).isTrue();
        assertThat(result.delivered()).isFalse();
    }

    @Test
    @DisplayName("variantId null → productId=null")
    void verify_variantIdNull_productIdNull() {
        when(orderItemQueryRepository.findOrderItemProjection(ORDER_ITEM_ID))
                .thenReturn(Optional.of(new OrderItemQueryRepository.OrderItemProjection(USER_ID, "delivered", null)));

        PurchaseVerificationPort.PurchaseVerification result = adapter.verify(ORDER_ITEM_ID, USER_ID);

        assertThat(result.ownedAndExists()).isTrue();
        assertThat(result.productId()).isNull();
    }

    @Test
    @DisplayName("ProductOrderCatalog로 productId 해석")
    void verify_productIdResolvedFromCatalog() {
        when(orderItemQueryRepository.findOrderItemProjection(ORDER_ITEM_ID))
                .thenReturn(Optional.of(new OrderItemQueryRepository.OrderItemProjection(USER_ID, "delivered", VARIANT_ID)));
        when(productOrderCatalog.getOrderableSnapshots(List.of(VARIANT_ID)))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID)));

        PurchaseVerificationPort.PurchaseVerification result = adapter.verify(ORDER_ITEM_ID, USER_ID);

        assertThat(result.productId()).isEqualTo(PRODUCT_ID);
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private ProductOrderCatalog.OrderableVariantSnapshot buildSnapshot(long variantId, long productId) {
        return new ProductOrderCatalog.OrderableVariantSnapshot(
                variantId, productId, "테스트 상품", "기본", List.of(),
                BigDecimal.valueOf(10000), true, 10, "ON_SALE", true, null
        );
    }
}
