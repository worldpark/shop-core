package com.shop.shop.order.service;

import com.shop.shop.common.exception.InvalidShipmentItemException;
import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderFulfillmentService 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>대상 선택: orderItemIds 생략 → 미발송 전부, 지정 → 해당 항목만</li>
 *   <li>첫 배송(paid) → rollup markPreparing 호출</li>
 *   <li>추가 배송(preparing) → markPreparing 미호출 (status 불변)</li>
 *   <li>상태 충돌 409: 주문 상태 오류 / 미발송 0건 / 이미 배정 항목 지정</li>
 *   <li>입력 오류 400: 미존재·타 주문 소속 orderItemId 지정 (모순3)</li>
 *   <li>미존재 주문 → 404</li>
 *   <li>락 우선(InOrder): findByIdForUpdate가 상태/항목 검증보다 먼저 호출</li>
 *   <li>409/400 throw 시 Shipment 저장·rollup 미수행(부작용 전 throw 검증)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderFulfillmentServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ShipmentRepository shipmentRepository;

    @InjectMocks
    private OrderFulfillmentService orderFulfillmentService;

    // ============================================================
    // 대상 선택 — orderItemIds 생략 = 미발송 전부
    // ============================================================

    @Test
    @DisplayName("orderItemIds 생략 → 미발송 항목 전부로 Shipment 1건 생성, rollup paid→preparing")
    void createShipment_noItemIds_allUnassigned() {
        // given
        Order order = createPaidOrderWithItems(10L, 20L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            setShipmentId(s, 100L);
            return s;
        });

        // when
        ShipmentResponse response = orderFulfillmentService.createShipment(1L, null);

        // then
        assertThat(response.status()).isEqualTo("preparing");
        assertThat(response.orderId()).isEqualTo(1L);
        assertThat(response.items()).hasSize(2);

        // rollup: paid → markPreparing 호출됨 (dirty checking — Order.status = "preparing")
        assertThat(order.getStatus()).isEqualTo("preparing");
    }

    @Test
    @DisplayName("orderItemIds 빈 목록 → 미발송 항목 전부로 배송 생성")
    void createShipment_emptyItemIds_allUnassigned() {
        Order order = createPaidOrderWithItems(10L, 20L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            setShipmentId(s, 101L);
            return s;
        });

        ShipmentResponse response = orderFulfillmentService.createShipment(1L, List.of());

        assertThat(response.items()).hasSize(2);
    }

    // ============================================================
    // 대상 선택 — orderItemIds 지정
    // ============================================================

    @Test
    @DisplayName("orderItemIds 지정 → 해당 항목만 포함")
    void createShipment_specifiedItemIds_onlyThoseItems() {
        Order order = createPaidOrderWithItems(10L, 20L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            setShipmentId(s, 102L);
            return s;
        });

        ShipmentResponse response = orderFulfillmentService.createShipment(1L, List.of(10L));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).orderItemId()).isEqualTo(10L);
    }

    // ============================================================
    // rollup — paid → preparing (첫 배송)
    // ============================================================

    @Test
    @DisplayName("첫 배송(paid 주문) → markPreparing 호출로 status=preparing rollup")
    void createShipment_paid_rollupToPreparing() {
        Order order = createPaidOrderWithItems(10L);
        assertThat(order.getStatus()).isEqualTo("paid");

        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            setShipmentId(s, 103L);
            return s;
        });

        orderFulfillmentService.createShipment(1L, null);

        assertThat(order.getStatus()).isEqualTo("preparing"); // rollup 반영
    }

    @Test
    @DisplayName("추가 배송(preparing 주문) → markPreparing 미호출, status 불변")
    void createShipment_alreadyPreparing_noRollup() {
        Order order = createOrderWithStatusAndItems("preparing", 10L, 20L);
        // item 10L 이미 배정됨, 20L 미발송
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of(10L));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            setShipmentId(s, 104L);
            return s;
        });

        orderFulfillmentService.createShipment(1L, null);

        // status 여전히 preparing (rollup 없음)
        assertThat(order.getStatus()).isEqualTo("preparing");
    }

    // ============================================================
    // 상태 충돌 409 — 부작용 전 throw
    // ============================================================

    @Test
    @DisplayName("주문 상태 pending → 409 OrderFulfillmentConflictException, Shipment 저장 미수행")
    void createShipment_pendingOrder_409_noSave() {
        Order order = createOrderWithStatusAndItems("pending", 10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, null))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("주문 상태 shipping → 409, Shipment 저장 미수행")
    void createShipment_shippingOrder_409_noSave() {
        Order order = createOrderWithStatusAndItems("shipping", 10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, null))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("주문 상태 delivered → 409, Shipment 저장 미수행")
    void createShipment_deliveredOrder_409_noSave() {
        Order order = createOrderWithStatusAndItems("delivered", 10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, null))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("주문 상태 cancelled → 409, Shipment 저장 미수행")
    void createShipment_cancelledOrder_409_noSave() {
        Order order = createOrderWithStatusAndItems("cancelled", 10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, null))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("주문 상태 refunded → 409, Shipment 저장 미수행")
    void createShipment_refundedOrder_409_noSave() {
        Order order = createOrderWithStatusAndItems("refunded", 10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, null))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("미발송 항목 0건(전부 이미 배정) → 409, Shipment 저장 미수행")
    void createShipment_noUnassignedItems_409_noSave() {
        Order order = createPaidOrderWithItems(10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of(10L)); // 이미 배정

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, null))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("지정 orderItemId가 이미 배정됨 → 409, Shipment 저장 미수행")
    void createShipment_alreadyAssignedItemId_409_noSave() {
        Order order = createPaidOrderWithItems(10L, 20L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of(10L)); // 10L 이미 배정

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, List.of(10L)))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        verify(shipmentRepository, never()).save(any());
    }

    // ============================================================
    // 입력 오류 400 — orderItemId 미존재/타 주문 소속 (모순3)
    // ============================================================

    @Test
    @DisplayName("지정 orderItemId가 해당 주문 소속이 아님 → 400 InvalidShipmentItemException (모순3)")
    void createShipment_unknownItemId_400_noSave() {
        Order order = createPaidOrderWithItems(10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of());

        // 999L: 해당 주문에 없는 itemId
        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, List.of(999L)))
                .isInstanceOf(InvalidShipmentItemException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("입력 오류(400)와 상태 충돌(409)은 서로 다른 예외 타입으로 분리됨 (모순3)")
    void createShipment_400vs409_differentExceptions() {
        // 400: 미존재 orderItemId
        Order order = createPaidOrderWithItems(10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(1L, List.of(999L)))
                .isInstanceOf(InvalidShipmentItemException.class)
                .isNotInstanceOf(OrderFulfillmentConflictException.class);
    }

    // ============================================================
    // 미존재 주문 → 404
    // ============================================================

    @Test
    @DisplayName("미존재 주문 → 404 OrderNotFoundException")
    void createShipment_orderNotFound_404() {
        when(orderRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderFulfillmentService.createShipment(999L, null))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ============================================================
    // 락 우선 InOrder 검증
    // ============================================================

    @Test
    @DisplayName("findByIdForUpdate(락)가 findAssignedOrderItemIds(항목 조회)보다 먼저 호출됨")
    void createShipment_lockBeforeValidation_inOrder() {
        Order order = createPaidOrderWithItems(10L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(shipmentRepository.findAssignedOrderItemIds(1L)).thenReturn(List.of());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            setShipmentId(s, 105L);
            return s;
        });

        orderFulfillmentService.createShipment(1L, null);

        InOrder inOrder = inOrder(orderRepository, shipmentRepository);
        inOrder.verify(orderRepository).findByIdForUpdate(1L);
        inOrder.verify(shipmentRepository).findAssignedOrderItemIds(1L);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Order createPaidOrderWithItems(long... itemIds) {
        return createOrderWithStatusAndItems("paid", itemIds);
    }

    private Order createOrderWithStatusAndItems(String status, long... itemIds) {
        Order order = Order.create(1L, "ORD-019-T-001", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);
        // status 필드 리플렉션 세팅
        try {
            java.lang.reflect.Field statusField = Order.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(order, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // id 필드 리플렉션 세팅
        try {
            java.lang.reflect.Field idField = Order.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // items 추가
        for (long itemId : itemIds) {
            OrderItem item = createOrderItem(itemId, "상품" + itemId);
            try {
                // order 참조를 item에 설정 (assignOrder는 package-private)
                java.lang.reflect.Field orderField = OrderItem.class.getDeclaredField("order");
                orderField.setAccessible(true);
                orderField.set(item, order);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            // order의 items 리스트에 직접 추가
            order.getItems().add(item);
        }
        return order;
    }

    private OrderItem createOrderItem(long id, String productName) {
        OrderItem item = OrderItem.create(id * 100L, productName, null,
                BigDecimal.valueOf(1000), 1);
        try {
            java.lang.reflect.Field idField = OrderItem.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(item, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return item;
    }

    private void setShipmentId(Shipment shipment, long id) {
        try {
            java.lang.reflect.Field idField = Shipment.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(shipment, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
