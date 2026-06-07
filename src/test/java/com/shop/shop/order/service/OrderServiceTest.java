package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import com.shop.shop.common.exception.EmptyCartException;
import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.OrderNumberGenerationException;
import com.shop.shop.common.exception.ProductNotPurchasableForOrderException;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderOptionValue;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderService} 단위 테스트 (Mockito).
 *
 * <p>LENIENT: mockSavedOrder 헬퍼가 다양한 필드를 stubbing하는데 각 테스트가 일부만 사용하므로
 * UnnecessaryStubbingException을 방지하기 위해 lenient strictness를 사용한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private CartCheckoutReader cartCheckoutReader;

    @Mock
    private ProductOrderCatalog productOrderCatalog;

    @Mock
    private InventoryStockPort inventoryStockPort;

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    private static final long USER_ID = 1L;
    private static final long VARIANT_ID_1 = 10L;
    private static final long VARIANT_ID_2 = 20L;

    private static final OrderCreateRequest VALID_REQUEST = new OrderCreateRequest(
            "홍길동", "010-1234-5678", "12345", "서울시 강남구", "101호"
    );

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                cartCheckoutReader, productOrderCatalog, inventoryStockPort, orderRepository);
        // self-injection: 단위 테스트에서 self = orderService 자신 (프록시 없이 직접 참조)
        orderService.self = orderService;
    }

    // =========================================================
    // 주문 생성 성공
    // =========================================================

    @Test
    @DisplayName("주문 생성 성공: 8단계 순서대로 호출됨")
    void createOrderTx_success_callsInCorrectOrder() {
        // given
        CartCheckoutItem item1 = new CartCheckoutItem(1L, VARIANT_ID_1, 2);
        CartCheckout checkout = new CartCheckout(100L, List.of(item1));
        when(cartCheckoutReader.getCheckoutCart(USER_ID)).thenReturn(checkout);

        OrderableVariantSnapshot advisory = makeSnapshot(VARIANT_ID_1, "상품A", new BigDecimal("1000"), true, 5);
        OrderableVariantSnapshot authorized = makeSnapshot(VARIANT_ID_1, "상품A", new BigDecimal("1100"), true, 5);
        when(productOrderCatalog.getOrderableSnapshots(any()))
                .thenReturn(List.of(advisory))
                .thenReturn(List.of(authorized));

        Order savedOrder = mockSavedOrder(100L, "ORD-20260101-000000-ABCD1234");
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        // when
        OrderService.OrderResult result = orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST-001");

        // then
        assertThat(result.orderId()).isEqualTo(100L);

        // 8단계 순서 검증
        InOrder ordered = inOrder(cartCheckoutReader, productOrderCatalog, inventoryStockPort, orderRepository);
        ordered.verify(cartCheckoutReader).getCheckoutCart(USER_ID);
        ordered.verify(productOrderCatalog).getOrderableSnapshots(any()); // 2단계 사전검증
        ordered.verify(inventoryStockPort).decrease(eq(VARIANT_ID_1), anyInt()); // 3단계 락+차감
        ordered.verify(productOrderCatalog).getOrderableSnapshots(any()); // 4단계 락 후 재조회
        ordered.verify(orderRepository).save(any(Order.class)); // 6단계 저장
        ordered.verify(cartCheckoutReader).clearCart(USER_ID); // 7단계 장바구니 비우기
    }

    @Test
    @DisplayName("저장용 스냅샷은 락 후 재조회 값(price=1100) 사용 — 2단계 advisory 값(1000) 아님")
    void createOrderTx_usesAuthorizedSnapshotPriceNotAdvisory() {
        // given
        CartCheckoutItem item = new CartCheckoutItem(1L, VARIANT_ID_1, 1);
        when(cartCheckoutReader.getCheckoutCart(USER_ID)).thenReturn(new CartCheckout(1L, List.of(item)));

        BigDecimal advisoryPrice = new BigDecimal("1000");
        BigDecimal authorizedPrice = new BigDecimal("1100");
        OrderableVariantSnapshot advisory = makeSnapshot(VARIANT_ID_1, "상품A", advisoryPrice, true, 5);
        OrderableVariantSnapshot authorized = makeSnapshot(VARIANT_ID_1, "상품A", authorizedPrice, true, 5);

        when(productOrderCatalog.getOrderableSnapshots(any()))
                .thenReturn(List.of(advisory))
                .thenReturn(List.of(authorized));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        Order savedOrder = mockSavedOrder(1L, "ORD-TEST");
        when(orderRepository.save(orderCaptor.capture())).thenReturn(savedOrder);

        // when
        orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST");

        // then: productOrderCatalog가 2번 호출됨(advisory + authorized)
        verify(productOrderCatalog, times(2)).getOrderableSnapshots(any());

        // 저장된 order의 itemsAmount = authorizedPrice × 1 = 1100
        Order capturedOrder = orderCaptor.getValue();
        assertThat(capturedOrder.getItemsAmount()).isEqualByComparingTo(authorizedPrice);
    }

    @Test
    @DisplayName("다중 variant: decrease 호출이 variantId 오름차순(10→20)")
    void createOrderTx_multipleVariants_decreaseInAscendingVariantIdOrder() {
        // given: variantId 20이 먼저 cart에 있어도 락은 10→20 순서여야 함
        CartCheckoutItem item2 = new CartCheckoutItem(2L, VARIANT_ID_2, 1);
        CartCheckoutItem item1 = new CartCheckoutItem(1L, VARIANT_ID_1, 1);
        when(cartCheckoutReader.getCheckoutCart(USER_ID))
                .thenReturn(new CartCheckout(1L, List.of(item2, item1)));

        OrderableVariantSnapshot snap1 = makeSnapshot(VARIANT_ID_1, "상품1", new BigDecimal("1000"), true, 5);
        OrderableVariantSnapshot snap2 = makeSnapshot(VARIANT_ID_2, "상품2", new BigDecimal("2000"), true, 5);
        when(productOrderCatalog.getOrderableSnapshots(any()))
                .thenReturn(List.of(snap1, snap2))
                .thenReturn(List.of(snap1, snap2));

        Order savedOrder = mockSavedOrder(1L, "ORD-TEST");
        when(orderRepository.save(any())).thenReturn(savedOrder);

        // when
        orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST");

        // then: decrease 호출 순서 — variantId 10 먼저, 20 나중
        InOrder decreaseOrder = inOrder(inventoryStockPort);
        decreaseOrder.verify(inventoryStockPort).decrease(eq(VARIANT_ID_1), anyInt());
        decreaseOrder.verify(inventoryStockPort).decrease(eq(VARIANT_ID_2), anyInt());
    }

    // =========================================================
    // 빈 장바구니
    // =========================================================

    @Test
    @DisplayName("빈 장바구니 → EmptyCartException(400), decrease/저장/clearCart 미호출")
    void createOrderTx_emptyCart_throwsEmptyCartException() {
        when(cartCheckoutReader.getCheckoutCart(USER_ID))
                .thenReturn(new CartCheckout(0L, List.of()));

        assertThatThrownBy(() -> orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST"))
                .isInstanceOf(EmptyCartException.class);

        verify(inventoryStockPort, never()).decrease(anyLong(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    // =========================================================
    // 구매 불가 (사전검증)
    // =========================================================

    @Test
    @DisplayName("구매 불가 variant(purchasable=false) → 409, decrease/저장 미호출")
    void createOrderTx_notPurchasable_throws409() {
        CartCheckoutItem item = new CartCheckoutItem(1L, VARIANT_ID_1, 1);
        when(cartCheckoutReader.getCheckoutCart(USER_ID))
                .thenReturn(new CartCheckout(1L, List.of(item)));

        OrderableVariantSnapshot notPurchasable = makeSnapshot(VARIANT_ID_1, "상품A", new BigDecimal("1000"), false, 5);
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of(notPurchasable));

        assertThatThrownBy(() -> orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST"))
                .isInstanceOf(ProductNotPurchasableForOrderException.class);

        verify(inventoryStockPort, never()).decrease(anyLong(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("catalog에 variantId 없음(삭제됨) → 409")
    void createOrderTx_variantMissingFromCatalog_throws409() {
        CartCheckoutItem item = new CartCheckoutItem(1L, VARIANT_ID_1, 1);
        when(cartCheckoutReader.getCheckoutCart(USER_ID))
                .thenReturn(new CartCheckout(1L, List.of(item)));

        // catalog 결과에 variant 없음
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST"))
                .isInstanceOf(ProductNotPurchasableForOrderException.class);
    }

    // =========================================================
    // 사전 재고 부족
    // =========================================================

    @Test
    @DisplayName("사전 stock 부족(quantity>snapshot.stock) → 409, decrease 미호출")
    void createOrderTx_advisoryStockInsufficient_throws409() {
        CartCheckoutItem item = new CartCheckoutItem(1L, VARIANT_ID_1, 10);
        when(cartCheckoutReader.getCheckoutCart(USER_ID))
                .thenReturn(new CartCheckout(1L, List.of(item)));

        OrderableVariantSnapshot snap = makeSnapshot(VARIANT_ID_1, "상품A", new BigDecimal("1000"), true, 3);
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of(snap));

        assertThatThrownBy(() -> orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST"))
                .isInstanceOf(InsufficientStockException.class);

        verify(inventoryStockPort, never()).decrease(anyLong(), anyInt());
    }

    // =========================================================
    // 락 후 재고 부족 (InsufficientStockException from InventoryStockPort)
    // =========================================================

    @Test
    @DisplayName("락 후 재고 부족(InventoryStockPort가 예외 throw) → 409 전파, 저장 미호출")
    void createOrderTx_inventoryPortThrows_propagates409() {
        CartCheckoutItem item = new CartCheckoutItem(1L, VARIANT_ID_1, 1);
        when(cartCheckoutReader.getCheckoutCart(USER_ID))
                .thenReturn(new CartCheckout(1L, List.of(item)));

        OrderableVariantSnapshot snap = makeSnapshot(VARIANT_ID_1, "상품A", new BigDecimal("1000"), true, 5);
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of(snap));

        doThrow(new InsufficientStockException()).when(inventoryStockPort).decrease(anyLong(), anyInt());

        assertThatThrownBy(() -> orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST"))
                .isInstanceOf(InsufficientStockException.class);

        verify(orderRepository, never()).save(any());
    }

    // =========================================================
    // 락 후 purchasable 재검증 실패
    // =========================================================

    @Test
    @DisplayName("락 후 product status HIDDEN(purchasable=false) 재검증 실패 → 409")
    void createOrderTx_postLockStatusNotPurchasable_throws409() {
        CartCheckoutItem item = new CartCheckoutItem(1L, VARIANT_ID_1, 1);
        when(cartCheckoutReader.getCheckoutCart(USER_ID))
                .thenReturn(new CartCheckout(1L, List.of(item)));

        // advisory: purchasable=true
        OrderableVariantSnapshot advisory = makeSnapshot(VARIANT_ID_1, "상품A", new BigDecimal("1000"), true, 5);
        // authorized(락 후 재조회): purchasable=false (상품 숨김)
        OrderableVariantSnapshot authorized = makeSnapshot(VARIANT_ID_1, "상품A", new BigDecimal("1000"), false, 5);

        when(productOrderCatalog.getOrderableSnapshots(any()))
                .thenReturn(List.of(advisory))
                .thenReturn(List.of(authorized));

        assertThatThrownBy(() -> orderService.createOrderTx(USER_ID, VALID_REQUEST, "ORD-TEST"))
                .isInstanceOf(ProductNotPurchasableForOrderException.class);

        verify(orderRepository, never()).save(any());
    }

    // =========================================================
    // placeOrder 재시도 (orderNumber 충돌)
    // =========================================================

    @Test
    @DisplayName("createOrderTx 1회 DataIntegrityViolation 후 2회째 성공 → placeOrder 최종 성공")
    void placeOrder_orderNumberConflictOnce_retries() {
        // given: self mock으로 OrderService.createOrderTx 동작 제어
        OrderService selfMock = mock(OrderService.class);
        orderService.self = selfMock;

        when(selfMock.createOrderTx(eq(USER_ID), eq(VALID_REQUEST), anyString()))
                .thenThrow(new DataIntegrityViolationException("orderNumber unique 충돌"))
                .thenReturn(new OrderService.OrderResult(1L, "ORD-RETRY-OK"));

        // when
        OrderService.OrderResult result = orderService.placeOrder(USER_ID, VALID_REQUEST);

        // then
        assertThat(result.orderId()).isEqualTo(1L);
        verify(selfMock, times(2)).createOrderTx(eq(USER_ID), eq(VALID_REQUEST), anyString());
    }

    @Test
    @DisplayName("createOrderTx 3회 DataIntegrityViolation → OrderNumberGenerationException")
    void placeOrder_orderNumberConflict3Times_throwsOrderNumberGenerationException() {
        OrderService selfMock = mock(OrderService.class);
        orderService.self = selfMock;

        when(selfMock.createOrderTx(eq(USER_ID), eq(VALID_REQUEST), anyString()))
                .thenThrow(new DataIntegrityViolationException("충돌"));

        assertThatThrownBy(() -> orderService.placeOrder(USER_ID, VALID_REQUEST))
                .isInstanceOf(OrderNumberGenerationException.class);

        verify(selfMock, times(3)).createOrderTx(eq(USER_ID), eq(VALID_REQUEST), anyString());
    }

    // =========================================================
    // 주문 목록 / 상세 조회
    // =========================================================

    @Test
    @DisplayName("getMyOrders: OrderRepository 호출 후 OrderSummary Page 반환")
    void getMyOrders_returnsOrderSummaryPage() {
        Order order = mockSavedOrder(1L, "ORD-001");
        PageRequest pageable = PageRequest.of(0, 10);
        when(orderRepository.findByUserIdOrderByCreatedAtDescIdDesc(USER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(order)));

        Page<OrderService.OrderSummary> result = orderService.getMyOrders(USER_ID, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getMyOrder: 타인 주문 상세 → OrderNotFoundException(404 존재 은닉)")
    void getMyOrder_otherUserOrder_throwsOrderNotFoundException() {
        when(orderRepository.findWithItemsByIdAndUserId(999L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getMyOrder(USER_ID, 999L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private OrderableVariantSnapshot makeSnapshot(long variantId, String productName,
                                                   BigDecimal price, boolean purchasable, int stock) {
        return new OrderableVariantSnapshot(
                variantId, 1L, productName,
                "옵션라벨", List.of(new OrderOptionValue("색상", "빨강", 0)),
                price, purchasable, stock,
                purchasable ? "ON_SALE" : "HIDDEN", purchasable
        );
    }

    private Order mockSavedOrder(long id, String orderNumber) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        when(order.getOrderNumber()).thenReturn(orderNumber);
        when(order.getStatus()).thenReturn("pending");
        when(order.getItemsAmount()).thenReturn(BigDecimal.TEN);
        when(order.getDiscountAmount()).thenReturn(BigDecimal.ZERO);
        when(order.getShippingFee()).thenReturn(BigDecimal.ZERO);
        when(order.getFinalAmount()).thenReturn(BigDecimal.TEN);
        when(order.getShipRecipient()).thenReturn("홍길동");
        when(order.getShipPhone()).thenReturn("010-0000-0000");
        when(order.getShipPostcode()).thenReturn("00000");
        when(order.getShipAddress1()).thenReturn("서울");
        when(order.getShipAddress2()).thenReturn("");
        when(order.getItems()).thenReturn(List.of());
        when(order.getCreatedAt()).thenReturn(Instant.now());
        return order;
    }
}
