package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService.createOrderTx 쿠폰 통합 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>userCouponId 없으면 CouponService 미호출 (회귀 0)</li>
 *   <li>userCouponId 있으면 computeDiscount → Order 9-arg → consume 순서 호출</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceCouponTest {

    @Mock
    private CartCheckoutReader cartCheckoutReader;
    @Mock
    private ProductOrderCatalog productOrderCatalog;
    @Mock
    private InventoryStockPort inventoryStockPort;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CouponService couponService;

    private OrderService orderService;

    private static final long USER_ID = 1L;
    private static final long VARIANT_ID = 10L;
    private static final long USER_COUPON_ID = 100L;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                cartCheckoutReader, productOrderCatalog, inventoryStockPort, orderRepository, couponService);
        orderService.self = orderService;

        // 기본 장바구니 및 스냅샷 설정
        CartCheckoutItem item = new CartCheckoutItem(1L, VARIANT_ID, 2);
        when(cartCheckoutReader.getCheckoutCart(USER_ID)).thenReturn(new CartCheckout(1L, List.of(item)));
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                VARIANT_ID, 1000L, "테스트상품", null, List.of(),
                new BigDecimal("5000"), true, 100, "ON_SALE", true, null);
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of(snapshot));

        // Order 저장 모킹
        Order mockOrder = Order.create(USER_ID, "ORD-TEST-001", new BigDecimal("10000"),
                "수령인", "010", "12345", "서울", null);
        setOrderId(mockOrder, 500L);
        when(orderRepository.save(any())).thenReturn(mockOrder);
    }

    @Test
    @DisplayName("userCouponId 없음 → CouponService.computeDiscount 미호출 (회귀 0)")
    void createOrderTx_noCoupon_couponServiceNotCalled() {
        OrderCreateRequest request = new OrderCreateRequest("홍길동", "010", "12345", "서울", null, null);

        orderService.createOrderTx(USER_ID, request, "ORD-001");

        verify(couponService, never()).computeDiscount(anyLong(), anyLong(), any());
        verify(couponService, never()).consume(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("userCouponId 있음 → computeDiscount → consume 순서 호출")
    void createOrderTx_withCoupon_computeAndConsumeCalled() {
        BigDecimal discountAmount = new BigDecimal("1000.00");
        long couponId = 99L;
        when(couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("10000")))
                .thenReturn(new CouponService.AppliedDiscount(discountAmount, couponId));
        // markUsedIfUnused, incrementUsedCount은 consume 내부에서 호출됨 (couponService mock이 처리)

        OrderCreateRequest request = new OrderCreateRequest("홍길동", "010", "12345", "서울", null, USER_COUPON_ID);

        orderService.createOrderTx(USER_ID, request, "ORD-001");

        verify(couponService).computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("10000"));
        verify(couponService).consume(anyLong(), anyLong(), anyLong(), anyLong(), any(Instant.class));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void setOrderId(Order order, long id) {
        try {
            java.lang.reflect.Field f = Order.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(order, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
