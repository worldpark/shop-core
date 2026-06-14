package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.dto.OrderCheckoutResponse;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderFacadeImpl} 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>MemberDirectory.findUserIdByEmail(email) 위임</li>
 *   <li>email → userId 변환 후 OrderService 위임</li>
 *   <li>020: getMyOrder에서 getShipments 합성 위임</li>
 *   <li>DTO 변환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderFacadeImplTest {

    @Mock
    private OrderService orderService;

    @Mock
    private MemberDirectory memberDirectory;

    @Mock
    private CartCheckoutReader cartCheckoutReader;

    @Mock
    private ProductOrderCatalog productOrderCatalog;

    @Mock
    private OrderFulfillmentService orderFulfillmentService;

    @Mock
    private OrderDtoMapper dtoMapper;

    private OrderFacadeImpl orderFacadeImpl;

    private static final String EMAIL = "consumer@example.com";
    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        orderFacadeImpl = new OrderFacadeImpl(
                orderService, memberDirectory, cartCheckoutReader, productOrderCatalog,
                orderFulfillmentService, dtoMapper);
    }

    @Test
    @DisplayName("getCheckout: purchasable 항목이 items에 채워진다")
    void getCheckout_purchasableItems_filledInItems() {
        long variantId = 10L;
        int quantity = 2;
        BigDecimal unitPrice = new BigDecimal("5000");
        BigDecimal expectedLineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));

        CartCheckoutItem cartItem = new CartCheckoutItem(1L, variantId, quantity);
        CartCheckout cartCheckout = new CartCheckout(1L, List.of(cartItem));

        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, 20L, "테스트 상품", "빨강 / L", List.of(),
                unitPrice, true, 10, "ON_SALE", true
        );

        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(cartCheckoutReader.getCheckoutCart(USER_ID)).thenReturn(cartCheckout);
        when(productOrderCatalog.getOrderableSnapshots(List.of(variantId))).thenReturn(List.of(snapshot));

        OrderCheckoutResponse result = orderFacadeImpl.getCheckout(EMAIL);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("테스트 상품");
        assertThat(result.items().get(0).unitPrice()).isEqualByComparingTo(unitPrice);
        assertThat(result.items().get(0).quantity()).isEqualTo(quantity);
        assertThat(result.items().get(0).lineAmount()).isEqualByComparingTo(expectedLineAmount);
        assertThat(result.itemsAmount()).isEqualByComparingTo(expectedLineAmount);
        assertThat(result.discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.shippingFee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.finalAmount()).isEqualByComparingTo(expectedLineAmount);
        assertThat(result.hasItems()).isTrue();

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(cartCheckoutReader).getCheckoutCart(USER_ID);
        verify(productOrderCatalog).getOrderableSnapshots(List.of(variantId));
    }

    @Test
    @DisplayName("getCheckout: 빈 장바구니이면 items=[] hasItems=false 반환")
    void getCheckout_emptyCart_returnsEmptyCheckout() {
        CartCheckout emptyCart = new CartCheckout(1L, List.of());

        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(cartCheckoutReader.getCheckoutCart(USER_ID)).thenReturn(emptyCart);

        OrderCheckoutResponse result = orderFacadeImpl.getCheckout(EMAIL);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasItems()).isFalse();
        assertThat(result.itemsAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getCheckout: purchasable=false 항목은 items에서 제외된다")
    void getCheckout_nonPurchasableItem_excludedFromItems() {
        long variantId = 11L;
        CartCheckoutItem cartItem = new CartCheckoutItem(2L, variantId, 1);
        CartCheckout cartCheckout = new CartCheckout(1L, List.of(cartItem));

        OrderableVariantSnapshot notPurchasable = new OrderableVariantSnapshot(
                variantId, 21L, "품절 상품", "", List.of(),
                new BigDecimal("3000"), true, 0, "SOLD_OUT", false
        );

        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(cartCheckoutReader.getCheckoutCart(USER_ID)).thenReturn(emptyCart(cartCheckout));
        when(productOrderCatalog.getOrderableSnapshots(List.of(variantId))).thenReturn(List.of(notPurchasable));

        OrderCheckoutResponse result = orderFacadeImpl.getCheckout(EMAIL);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasItems()).isFalse();
        assertThat(result.itemsAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("createOrder: email → MemberDirectory → userId → OrderService.placeOrder 위임, shipments=List.of()")
    void createOrder_convertsEmailToUserIdViaDirectory() {
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(orderService.placeOrder(eq(USER_ID), any())).thenReturn(new OrderService.OrderResult(1L, "ORD-001"));
        when(orderService.getMyOrder(eq(USER_ID), eq(1L))).thenReturn(makeOrderDetail(1L));
        when(dtoMapper.toOrderResponse(any(), eq(List.of()))).thenReturn(makeOrderResponse(1L));

        OrderCreateRequest request = new OrderCreateRequest("홍", "010", "12345", "서울", null, null);
        OrderResponse result = orderFacadeImpl.createOrder(EMAIL, request);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(orderService).placeOrder(eq(USER_ID), eq(request));
        assertThat(result.orderId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getMyOrders: email → userId 변환 후 orderService.getMyOrders 위임")
    void getMyOrders_delegatesToOrderService() {
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(orderService.getMyOrders(eq(USER_ID), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(List.of())
        );

        orderFacadeImpl.getMyOrders(EMAIL, org.springframework.data.domain.PageRequest.of(0, 10));

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(orderService).getMyOrders(eq(USER_ID), any());
    }

    @Test
    @DisplayName("getMyOrder: email → userId 변환 후 orderService.getMyOrder + getShipments 합성 위임 (020)")
    void getMyOrder_delegatesToOrderServiceAndComposesShipments() {
        long orderId = 99L;
        List<ShipmentResponse> shipments = List.of();
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(orderService.getMyOrder(eq(USER_ID), eq(orderId))).thenReturn(makeOrderDetail(orderId));
        when(orderFulfillmentService.getShipments(orderId)).thenReturn(shipments);
        when(dtoMapper.toOrderResponse(any(), eq(shipments))).thenReturn(makeOrderResponse(orderId));

        OrderResponse result = orderFacadeImpl.getMyOrder(EMAIL, orderId);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(orderService).getMyOrder(eq(USER_ID), eq(orderId));
        verify(orderFulfillmentService).getShipments(orderId);
        assertThat(result.orderId()).isEqualTo(orderId);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private CartCheckout emptyCart(CartCheckout nonEmpty) {
        // purchasable=false 항목이 있는 cart를 그대로 반환
        return nonEmpty;
    }

    private OrderService.OrderDetail makeOrderDetail(long orderId) {
        return new OrderService.OrderDetail(
                orderId, "ORD-001", "pending", List.of(),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                "홍", "010", "12345", "서울", null, Instant.now()
        );
    }

    private OrderResponse makeOrderResponse(long orderId) {
        return new OrderResponse(orderId, "ORD-001", "pending", List.of(),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                new com.shop.shop.order.dto.ShippingAddressResponse("홍", "010", "12345", "서울", null),
                Instant.now(), List.of());
    }
}
