package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.domain.ShipmentItem;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderFulfillmentService.ship() 단위 테스트 (Task 020, Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>preparing → shipping 정상 전이: status/carrier/trackingNumber/shippedAt 설정 + rollup + 이벤트 발행</li>
 *   <li>멀티 배송(주문 이미 shipping): 주문 rollup 미실행</li>
 *   <li>멱등성(shipment 이미 shipping): markShipping·이벤트 미실행, 기존 ShipmentResponse 반환</li>
 *   <li>P2 사전검증: variantId null → 409, 스냅샷 미반환 → 409, 비활성/품절은 통과</li>
 *   <li>주문 취소/환불 → 409</li>
 *   <li>방어 가드: 주문 상태 pending/paid → 409</li>
 *   <li>shipment 상태 delivered → 409</li>
 *   <li>미존재 shipment → 404 ShipmentNotFoundException</li>
 *   <li>정합1 락 순서: findOrderIdById → findByIdForUpdate → findById (InOrder)</li>
 *   <li>409/404 throw 시 이벤트 미발행</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderFulfillmentServiceShipTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private MemberDirectory memberDirectory;

    @Mock
    private ProductOrderCatalog productOrderCatalog;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderFulfillmentService orderFulfillmentService;

    private static final long SHIPMENT_ID = 100L;
    private static final long ORDER_ID = 1L;
    private static final long ORDER_ITEM_ID = 10L;
    private static final long VARIANT_ID = 200L;
    private static final long PRODUCT_ID = 300L;

    // ============================================================
    // 정상 전이 — preparing → shipping
    // ============================================================

    @Test
    @DisplayName("ship: preparing 배송 → shipping 전이 성공, status=shipping")
    void ship_preparingShipment_statusBecomesShipping() {
        setupHappyPath("preparing");

        ShipmentResponse result = orderFulfillmentService.ship(SHIPMENT_ID, "CJ대한통운", "TRK-001");

        assertThat(result.status()).isEqualTo("shipping");
    }

    @Test
    @DisplayName("ship: carrier/trackingNumber ShipmentResponse에 반영됨")
    void ship_carrierAndTrackingNumberInResponse() {
        setupHappyPath("preparing");

        ShipmentResponse result = orderFulfillmentService.ship(SHIPMENT_ID, "한진택배", "HJ-99999");

        assertThat(result.carrier()).isEqualTo("한진택배");
        assertThat(result.trackingNumber()).isEqualTo("HJ-99999");
    }

    @Test
    @DisplayName("ship: shippedAt이 null이 아님 (Instant.now() 설정)")
    void ship_shippedAtIsNotNull() {
        setupHappyPath("preparing");

        ShipmentResponse result = orderFulfillmentService.ship(SHIPMENT_ID, "CJ대한통운", "TRK-001");

        assertThat(result.shippedAt()).isNotNull();
    }

    @Test
    @DisplayName("ship: 이벤트 발행됨 (ApplicationEventPublisher.publishEvent)")
    void ship_eventIsPublished() {
        setupHappyPath("preparing");

        orderFulfillmentService.ship(SHIPMENT_ID, "CJ대한통운", "TRK-001");

        // ShippingStartedEvent는 ApplicationEvent 미상속 → publishEvent(Object) 오버로드 호출
        verify(eventPublisher).publishEvent(any(com.shop.shop.order.event.ShippingStartedEvent.class));
    }

    @Test
    @DisplayName("ship: 주문 preparing → shipping rollup 수행됨")
    void ship_orderPreparingRollupToShipping() {
        // Order에 OrderItem 포함 (buildShippingStartedEvent의 orderItemMap 구성용)
        Order order = createOrderWithItemAndStatus("preparing", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        setupMemberAndCatalog(VARIANT_ID, PRODUCT_ID);

        orderFulfillmentService.ship(SHIPMENT_ID, "CJ대한통운", "TRK-001");

        assertThat(order.getStatus()).isEqualTo("shipping");
    }

    // ============================================================
    // 멀티 배송 — 주문 이미 shipping, rollup skip
    // ============================================================

    @Test
    @DisplayName("ship: 주문 이미 shipping → rollup 미실행, 주문 status 불변")
    void ship_orderAlreadyShipping_noRollup() {
        Order order = createOrderWithItemAndStatus("shipping", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        setupMemberAndCatalog(VARIANT_ID, PRODUCT_ID);

        ShipmentResponse result = orderFulfillmentService.ship(SHIPMENT_ID, "CJ대한통운", "TRK-001");

        assertThat(order.getStatus()).isEqualTo("shipping"); // rollup 안 함, 불변
        assertThat(result.status()).isEqualTo("shipping");
    }

    // ============================================================
    // 멱등성 — shipment 이미 shipping
    // ============================================================

    @Test
    @DisplayName("ship: shipment 이미 shipping → 멱등 반환, markShipping 미실행")
    void ship_shipmentAlreadyShipping_idempotentReturn() {
        Order order = createOrderWithItemAndStatus("shipping", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("shipping", ORDER_ITEM_ID);
        // 이미 shipping 상태이면 carrier/trackingNumber가 설정되어 있음
        setField(shipment, "carrier", "기존택배");
        setField(shipment, "trackingNumber", "기존TRK");
        setField(shipment, "shippedAt", Instant.now());

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        ShipmentResponse result = orderFulfillmentService.ship(SHIPMENT_ID, "다른택배", "다른TRK");

        // 이벤트 미발행
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        // 기존 값 유지 (새 carrier/trackingNumber 반영 안 됨, 정합4)
        assertThat(result.carrier()).isEqualTo("기존택배");
        assertThat(result.status()).isEqualTo("shipping");
    }

    // ============================================================
    // P2 사전검증
    // ============================================================

    @Test
    @DisplayName("ship: variantId null → 409 OrderFulfillmentConflictException")
    void ship_variantIdNull_409() {
        // Order에 variantId=null 인 OrderItem 포함
        Order order = createOrderWithNullVariantItem("preparing", ORDER_ITEM_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("ship: 스냅샷 미반환(variant 행 삭제) → 409")
    void ship_snapshotMissing_409() {
        Order order = createOrderWithItemAndStatus("preparing", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        // 스냅샷 반환 없음 (빈 목록 = variant 행 삭제)
        // memberDirectory 스텁 불필요 — getOrderableSnapshots 실패 후 409 throw, member 조회 미도달
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of());

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("ship: 비활성·품절 variant → P2 통과, 정상 진행 (정합5)")
    void ship_inactiveOrOutOfStock_p2Pass() {
        Order order = createOrderWithItemAndStatus("preparing", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        // 비활성 + 품절이지만 스냅샷은 반환됨 → P2 통과
        OrderableVariantSnapshot inactiveSnapshot = new OrderableVariantSnapshot(
                VARIANT_ID, PRODUCT_ID, "상품명", "옵션", List.of(),
                BigDecimal.valueOf(10000), false, 0, "SOLD_OUT", false
        );
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of(inactiveSnapshot));
        when(memberDirectory.findContactByUserId(any(long.class)))
                .thenReturn(new MemberDirectory.MemberContact("user@example.com", "홍길동"));

        // 예외 없이 정상 처리
        ShipmentResponse result = orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK");

        assertThat(result.status()).isEqualTo("shipping");
        verify(eventPublisher).publishEvent(any(com.shop.shop.order.event.ShippingStartedEvent.class));
    }

    // ============================================================
    // 주문 취소/환불 → 409
    // ============================================================

    @Test
    @DisplayName("ship: 주문 cancelled → 409, 이벤트 미발행")
    void ship_orderCancelled_409() {
        Order order = createOrderWithItemAndStatus("cancelled", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("ship: 주문 refunded → 409, 이벤트 미발행")
    void ship_orderRefunded_409() {
        Order order = createOrderWithItemAndStatus("refunded", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ============================================================
    // 방어 가드 — 주문 상태 pending/paid
    // ============================================================

    @Test
    @DisplayName("ship: 주문 pending → 방어 가드 409")
    void ship_orderPending_409() {
        Order order = createOrderWithItemAndStatus("pending", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(OrderFulfillmentConflictException.class);
    }

    @Test
    @DisplayName("ship: 주문 paid → 방어 가드 409 (이행 전 배송 시작 불가)")
    void ship_orderPaid_409() {
        Order order = createOrderWithItemAndStatus("paid", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(OrderFulfillmentConflictException.class);
    }

    // ============================================================
    // shipment 상태 delivered → 409
    // ============================================================

    @Test
    @DisplayName("ship: shipment delivered → 역방향 전이 불가 409")
    void ship_shipmentDelivered_409() {
        Order order = createOrderWithItemAndStatus("preparing", ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("delivered", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ============================================================
    // 미존재 shipment → 404
    // ============================================================

    @Test
    @DisplayName("ship: shipment 미존재 → 404 ShipmentNotFoundException")
    void ship_shipmentNotFound_404() {
        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderFulfillmentService.ship(SHIPMENT_ID, "CJ", "TRK"))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    // ============================================================
    // 정합1 락 순서 — InOrder
    // ============================================================

    @Test
    @DisplayName("정합1: findOrderIdById(스칼라) → findByIdForUpdate(락) → findById(shipment) 순서 보장")
    void ship_lockOrderingInOrder() {
        setupHappyPath("preparing");

        orderFulfillmentService.ship(SHIPMENT_ID, "CJ대한통운", "TRK-001");

        InOrder inOrder = inOrder(shipmentRepository, orderRepository);
        inOrder.verify(shipmentRepository).findOrderIdById(SHIPMENT_ID);
        inOrder.verify(orderRepository).findByIdForUpdate(ORDER_ID);
        inOrder.verify(shipmentRepository).findById(SHIPMENT_ID);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    /**
     * 정상 경로 셋업 — 주문 orderStatus에서 shipment preparing 상태.
     * Order에 OrderItem을 추가해 buildShippingStartedEvent의 orderItemMap 조회가 성공하게 한다.
     */
    private void setupHappyPath(String orderStatus) {
        Order order = createOrderWithItemAndStatus(orderStatus, ORDER_ITEM_ID, VARIANT_ID);
        Shipment shipment = createShipmentLinkedToOrder("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        setupMemberAndCatalog(VARIANT_ID, PRODUCT_ID);
    }

    private void setupMemberAndCatalog(long variantId, long productId) {
        when(memberDirectory.findContactByUserId(any(long.class)))
                .thenReturn(new MemberDirectory.MemberContact("user@example.com", "홍길동"));
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, "테스트 상품", "빨강/L", List.of(),
                BigDecimal.valueOf(10000), true, 5, "ON_SALE", true
        );
        when(productOrderCatalog.getOrderableSnapshots(any())).thenReturn(List.of(snapshot));
    }

    /**
     * Order 생성 (주어진 status, orderItemId, variantId로 OrderItem 포함).
     * service의 orderItemMap 구성에 사용하는 Order와 동일 인스턴스여야 한다.
     */
    private Order createOrderWithItemAndStatus(String status, long orderItemId, long variantId) {
        Order order = Order.create(1L, "ORD-020-TEST", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);
        setOrderId(order, ORDER_ID);
        setField(order, "status", status);

        OrderItem orderItem = OrderItem.create(variantId, "테스트 상품", "옵션",
                BigDecimal.valueOf(10000), 1);
        setField(orderItem, "id", orderItemId);
        setField(orderItem, "order", order);
        order.getItems().add(orderItem);

        return order;
    }

    /**
     * variantId null 케이스 — OrderItem의 variantId를 null로 설정.
     */
    private Order createOrderWithNullVariantItem(String status, long orderItemId) {
        Order order = Order.create(1L, "ORD-020-TEST", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);
        setOrderId(order, ORDER_ID);
        setField(order, "status", status);

        // variantId null: OrderItem을 만들되 variantId 필드를 null로 덮어씀
        OrderItem orderItem = OrderItem.create(VARIANT_ID, "테스트 상품", "옵션",
                BigDecimal.valueOf(10000), 1);
        setField(orderItem, "id", orderItemId);
        setField(orderItem, "variantId", null);
        setField(orderItem, "order", order);
        order.getItems().add(orderItem);

        return order;
    }

    /**
     * Shipment 생성 — items는 주어진 orderItemId의 ShipmentItem 1개.
     * ship() 메서드는 findByIdForUpdate로 받은 Order에서 orderItemMap을 구성하므로
     * Shipment의 ShipmentItem.orderItemId가 그 Order의 OrderItem.id와 일치해야 한다.
     */
    private Shipment createShipmentLinkedToOrder(String status, long orderItemId) {
        Shipment shipment = Shipment.preparing(ORDER_ID);
        setShipmentId(shipment, SHIPMENT_ID);
        setField(shipment, "status", status);

        ShipmentItem si = ShipmentItem.of(orderItemId);
        setField(si, "shipment", shipment);
        shipment.getItems().add(si);

        return shipment;
    }

    private void setOrderId(Order order, long id) {
        setField(order, "id", id);
    }

    private void setShipmentId(Shipment shipment, long id) {
        setField(shipment, "id", id);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("필드 설정 실패: " + fieldName, e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("필드 미존재: " + fieldName + " in " + clazz.getName());
    }
}
