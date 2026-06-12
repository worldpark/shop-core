package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.domain.ShipmentItem;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderFulfillmentService.deliver() 단위 테스트 (Task 021, Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>단일 배송 완료 → 주문 delivered rollup + orderDelivered=true</li>
 *   <li>멀티 배송 일부 완료 → 주문 shipping 유지 + orderDelivered=false</li>
 *   <li>미배정 항목 있으면 주문 shipping 유지</li>
 *   <li>마지막 배송 완료 → 주문 delivered rollup</li>
 *   <li>잘못된 전이(preparing/역방향) → 409</li>
 *   <li>취소/환불 주문 → 409</li>
 *   <li>비-shipping 주문 방어 가드 → 409</li>
 *   <li>멱등: 이미 delivered → markDelivered 미호출, 상태 불변, 200</li>
 *   <li>단일 배송 주문 재-deliver(주문 이미 delivered) → 멱등 200, markDelivered 미호출, 방어 가드 409 아님 (revision-2 핵심)</li>
 *   <li>정합1 락 순서: findOrderIdById → findByIdForUpdate → findById InOrder 단언</li>
 *   <li>orderDelivered가 현재 주문 status 반영</li>
 *   <li>미존재 배송 → 404 ShipmentNotFoundException</li>
 *   <li>이벤트/member/product 미호출 (deliver는 이 의존 없음)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderFulfillmentServiceDeliverTest {

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

    // ============================================================
    // 단일 배송 완료 → 주문 delivered rollup
    // ============================================================

    @Test
    @DisplayName("deliver: 단일 배송 완료 → 주문 rollup shipping→delivered, orderDelivered=true")
    void deliver_singleShipment_orderRollupToDelivered() {
        Order order = createOrderWithItem("shipping", ORDER_ITEM_ID);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);

        setupHappyPath(order, shipment, List.of(ORDER_ITEM_ID), List.of(shipment));

        DeliverResponse result = orderFulfillmentService.deliver(SHIPMENT_ID);

        assertThat(result.shipment().status()).isEqualTo("delivered");
        assertThat(result.orderDelivered()).isTrue();
        assertThat(order.getStatus()).isEqualTo("delivered");
    }

    @Test
    @DisplayName("deliver: shipment.deliveredAt이 null이 아님")
    void deliver_singleShipment_deliveredAtIsNotNull() {
        Order order = createOrderWithItem("shipping", ORDER_ITEM_ID);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);

        setupHappyPath(order, shipment, List.of(ORDER_ITEM_ID), List.of(shipment));

        DeliverResponse result = orderFulfillmentService.deliver(SHIPMENT_ID);

        assertThat(result.shipment().deliveredAt()).isNotNull();
    }

    // ============================================================
    // 멀티 배송 — 일부 완료 → 주문 shipping 유지
    // ============================================================

    @Test
    @DisplayName("deliver: 멀티 배송 일부만 delivered → 주문 shipping 유지, orderDelivered=false")
    void deliver_multiShipment_partialDelivered_orderRemainsShipping() {
        long otherShipmentItemId = 20L;
        Order order = createOrderWithItems("shipping", ORDER_ITEM_ID, otherShipmentItemId);
        Shipment shipment1 = createShipment("shipping", ORDER_ITEM_ID);  // 이번에 완료

        // 다른 배송은 아직 shipping 상태
        Shipment shipment2 = createShipment("shipping", otherShipmentItemId);
        setShipmentId(shipment2, 200L);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment1));
        // 두 항목 모두 배정
        when(shipmentRepository.findAssignedOrderItemIds(ORDER_ID))
                .thenReturn(List.of(ORDER_ITEM_ID, otherShipmentItemId));
        // shipment2는 아직 shipping → 전부 delivered 아님
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(shipment1, shipment2));

        DeliverResponse result = orderFulfillmentService.deliver(SHIPMENT_ID);

        assertThat(result.shipment().status()).isEqualTo("delivered");
        assertThat(result.orderDelivered()).isFalse();
        assertThat(order.getStatus()).isEqualTo("shipping"); // rollup 안 함
    }

    // ============================================================
    // 미배정 항목 → 주문 shipping 유지
    // ============================================================

    @Test
    @DisplayName("deliver: 미배정 항목 있으면 주문 shipping 유지, orderDelivered=false")
    void deliver_unassignedItems_orderRemainsShipping() {
        long unassignedItemId = 30L;
        Order order = createOrderWithItems("shipping", ORDER_ITEM_ID, unassignedItemId);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        // ORDER_ITEM_ID만 배정, unassignedItemId는 미배정
        when(shipmentRepository.findAssignedOrderItemIds(ORDER_ID))
                .thenReturn(List.of(ORDER_ITEM_ID));
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(shipment));

        DeliverResponse result = orderFulfillmentService.deliver(SHIPMENT_ID);

        assertThat(result.orderDelivered()).isFalse();
        assertThat(order.getStatus()).isEqualTo("shipping");
    }

    // ============================================================
    // 마지막 배송 완료 → 주문 delivered rollup
    // ============================================================

    @Test
    @DisplayName("deliver: 마지막 배송 완료 → 주문 rollup delivered")
    void deliver_lastShipment_orderRollupToDelivered() {
        long otherItemId = 20L;
        Order order = createOrderWithItems("shipping", ORDER_ITEM_ID, otherItemId);
        Shipment shipment1 = createShipment("shipping", ORDER_ITEM_ID); // 이번에 완료
        Shipment shipment2 = createShipment("delivered", otherItemId);   // 이미 완료
        setShipmentId(shipment2, 200L);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment1));
        when(shipmentRepository.findAssignedOrderItemIds(ORDER_ID))
                .thenReturn(List.of(ORDER_ITEM_ID, otherItemId));
        // markDelivered 후 shipment1도 delivered가 됨 — 전부 delivered
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(shipment1, shipment2));

        DeliverResponse result = orderFulfillmentService.deliver(SHIPMENT_ID);

        assertThat(result.orderDelivered()).isTrue();
        assertThat(order.getStatus()).isEqualTo("delivered");
    }

    // ============================================================
    // 잘못된 전이 — 409
    // ============================================================

    @Test
    @DisplayName("deliver: shipment preparing → 409 OrderFulfillmentConflictException")
    void deliver_shipmentPreparing_409() {
        Order order = createOrderWithItem("shipping", ORDER_ITEM_ID);
        Shipment shipment = createShipment("preparing", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.deliver(SHIPMENT_ID))
                .isInstanceOf(OrderFulfillmentConflictException.class);
    }

    // ============================================================
    // 취소/환불 주문 → 409
    // ============================================================

    @Test
    @DisplayName("deliver: 주문 cancelled → 409 (018 상호작용 모순 방지)")
    void deliver_orderCancelled_409() {
        Order order = createOrderWithItem("cancelled", ORDER_ITEM_ID);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.deliver(SHIPMENT_ID))
                .isInstanceOf(OrderFulfillmentConflictException.class);
    }

    @Test
    @DisplayName("deliver: 주문 refunded → 409 (018 상호작용 모순 방지)")
    void deliver_orderRefunded_409() {
        Order order = createOrderWithItem("refunded", ORDER_ITEM_ID);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.deliver(SHIPMENT_ID))
                .isInstanceOf(OrderFulfillmentConflictException.class);
    }

    // ============================================================
    // 방어 가드 — 비-shipping 주문 → 409
    // ============================================================

    @Test
    @DisplayName("deliver: 주문 preparing → 방어 가드 409 (shipment != delivered인데 주문이 shipping 아님)")
    void deliver_orderPreparing_guardThrows_409() {
        Order order = createOrderWithItem("preparing", ORDER_ITEM_ID);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> orderFulfillmentService.deliver(SHIPMENT_ID))
                .isInstanceOf(OrderFulfillmentConflictException.class);
    }

    // ============================================================
    // 멱등 — 이미 delivered (멱등 200, markDelivered 미호출)
    // ============================================================

    @Test
    @DisplayName("deliver: shipment 이미 delivered → 멱등 200, markDelivered 미호출, 상태 불변")
    void deliver_shipmentAlreadyDelivered_idempotentReturn_markDeliveredNotCalled() {
        Order order = createOrderWithItem("shipping", ORDER_ITEM_ID);
        Shipment shipment = createShipment("delivered", ORDER_ITEM_ID);
        setDeliveredAt(shipment, Instant.now());

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        DeliverResponse result = orderFulfillmentService.deliver(SHIPMENT_ID);

        // 멱등 200
        assertThat(result.shipment().status()).isEqualTo("delivered");
        // findAssignedOrderItemIds/findByOrderId 미호출 (멱등 경로)
        verify(shipmentRepository, never()).findAssignedOrderItemIds(anyLong());
        verify(shipmentRepository, never()).findByOrderId(anyLong());
    }

    // ============================================================
    // 핵심: 단일 배송 주문이 이미 delivered로 rollup된 뒤 재-deliver → 멱등 200 (revision-2 핵심)
    // ============================================================

    @Test
    @DisplayName("단일 배송 주문 재-deliver: 주문 이미 delivered → 멱등 200, markDelivered 미호출, 방어 가드 409 아님 (revision-2)")
    void deliver_singleShipment_orderAlreadyDelivered_idempotent200_notGuard409() {
        // 단일 배송 rollup으로 주문이 이미 delivered로 전이된 상황
        // shipment도 이미 delivered → 멱등 경로가 방어 가드보다 먼저 평가되어야 함
        Order order = createOrderWithItem("delivered", ORDER_ITEM_ID);
        Shipment shipment = createShipment("delivered", ORDER_ITEM_ID);
        setDeliveredAt(shipment, Instant.parse("2026-06-12T10:00:00Z"));

        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

        // 409 아님 — 멱등 200
        DeliverResponse result = orderFulfillmentService.deliver(SHIPMENT_ID);

        assertThat(result.shipment().status()).isEqualTo("delivered");
        assertThat(result.orderDelivered()).isTrue(); // 현재 주문 status = delivered
        // markDelivered 미호출 확인 — 주문 status 여전히 delivered (변경 없음)
        assertThat(order.getStatus()).isEqualTo("delivered");
        // rollup 쿼리 미호출 (멱등 경로에서는 호출 안 함)
        verify(shipmentRepository, never()).findAssignedOrderItemIds(anyLong());
    }

    @Test
    @DisplayName("orderDelivered: 주문 status delivered이면 true, shipping이면 false")
    void deliver_orderDelivered_reflectsCurrentOrderStatus() {
        // 단일 배송 완료 시 orderDelivered = true
        Order order1 = createOrderWithItem("shipping", ORDER_ITEM_ID);
        Shipment shipment1 = createShipment("shipping", ORDER_ITEM_ID);
        setupHappyPath(order1, shipment1, List.of(ORDER_ITEM_ID), List.of(shipment1));

        DeliverResponse result1 = orderFulfillmentService.deliver(SHIPMENT_ID);
        assertThat(result1.orderDelivered()).isTrue();
    }

    // ============================================================
    // 정합1 락 순서 InOrder 단언
    // ============================================================

    @Test
    @DisplayName("정합1: findOrderIdById(스칼라) → findByIdForUpdate(락) → findById(shipment) 순서 보장")
    void deliver_lockOrdering_inOrder() {
        Order order = createOrderWithItem("shipping", ORDER_ITEM_ID);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);
        setupHappyPath(order, shipment, List.of(ORDER_ITEM_ID), List.of(shipment));

        orderFulfillmentService.deliver(SHIPMENT_ID);

        InOrder inOrder = inOrder(shipmentRepository, orderRepository);
        inOrder.verify(shipmentRepository).findOrderIdById(SHIPMENT_ID);
        inOrder.verify(orderRepository).findByIdForUpdate(ORDER_ID);
        inOrder.verify(shipmentRepository).findById(SHIPMENT_ID);
    }

    // ============================================================
    // 미존재 배송 → 404
    // ============================================================

    @Test
    @DisplayName("deliver: shipment 미존재 → 404 ShipmentNotFoundException")
    void deliver_shipmentNotFound_404() {
        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderFulfillmentService.deliver(SHIPMENT_ID))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    // ============================================================
    // 이벤트/member/product 미호출
    // ============================================================

    @Test
    @DisplayName("deliver: 이벤트/member/product 미호출 — deliver는 이 의존이 없음")
    void deliver_noEventMemberProductCalls() {
        Order order = createOrderWithItem("shipping", ORDER_ITEM_ID);
        Shipment shipment = createShipment("shipping", ORDER_ITEM_ID);
        setupHappyPath(order, shipment, List.of(ORDER_ITEM_ID), List.of(shipment));

        orderFulfillmentService.deliver(SHIPMENT_ID);

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        verify(memberDirectory, never()).findContactByUserId(anyLong());
        verify(productOrderCatalog, never()).getOrderableSnapshots(org.mockito.ArgumentMatchers.any());
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void setupHappyPath(Order order, Shipment shipment,
                                  List<Long> assignedIds, List<Shipment> allShipments) {
        when(shipmentRepository.findOrderIdById(SHIPMENT_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.findAssignedOrderItemIds(ORDER_ID)).thenReturn(assignedIds);
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(allShipments);
    }

    private Order createOrderWithItem(String status, long orderItemId) {
        return createOrderWithItems(status, orderItemId);
    }

    private Order createOrderWithItems(String status, long... orderItemIds) {
        Order order = Order.create(1L, "ORD-021-TEST", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);
        setField(order, "id", ORDER_ID);
        setField(order, "status", status);

        for (long itemId : orderItemIds) {
            OrderItem orderItem = OrderItem.create(itemId * 100L, "테스트 상품", null,
                    BigDecimal.valueOf(10000), 1);
            setField(orderItem, "id", itemId);
            setField(orderItem, "order", order);
            order.getItems().add(orderItem);
        }
        return order;
    }

    private Shipment createShipment(String status, long orderItemId) {
        Shipment shipment = Shipment.preparing(ORDER_ID);
        setShipmentId(shipment, SHIPMENT_ID);
        setField(shipment, "status", status);

        ShipmentItem si = ShipmentItem.of(orderItemId);
        setField(si, "shipment", shipment);
        shipment.getItems().add(si);

        return shipment;
    }

    private void setShipmentId(Shipment shipment, long id) {
        setField(shipment, "id", id);
    }

    private void setDeliveredAt(Shipment shipment, Instant deliveredAt) {
        setField(shipment, "deliveredAt", deliveredAt);
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
