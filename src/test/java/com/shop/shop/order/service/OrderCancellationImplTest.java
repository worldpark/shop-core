package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.event.OrderCancelledEvent;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.OrderCancellationResult;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
import com.shop.shop.product.spi.ProductOrderCatalog;
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
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OrderCancellationImpl 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>refunded=true → Order.markRefunded() 호출, cancelled=false → Order.markCancelled()</li>
 *   <li>재고 복원: variantId 오름차순 + 항목별 quantity로 increase 호출</li>
 *   <li>variantId null 항목 skip+log — 나머지 정상 복원</li>
 *   <li>OrderCancelledEvent 전 필드 매핑 (memberEmail/memberName=member.spi, items.productId=product.spi)</li>
 *   <li>ALREADY_CANCELLED: 이미 cancelled/refunded → 멱등 반환(재복원·재발행 없음)</li>
 *   <li>REJECTED: 이행단계 → 값 반환(정상 흐름엔 발생하지 않지만 OrderCancellationImpl 자체 분기 검증)</li>
 *   <li>소유권 불일치 → OrderNotFoundException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCancellationImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private InventoryStockPort inventoryStockPort;
    @Mock
    private MemberDirectory memberDirectory;
    @Mock
    private ProductOrderCatalog productOrderCatalog;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CouponService couponService;

    private OrderCancellationImpl cancellationImpl;

    private static final long ORDER_ID = 10L;
    private static final long USER_ID = 5L;

    @BeforeEach
    void setUp() {
        cancellationImpl = new OrderCancellationImpl(
                orderRepository, inventoryStockPort, memberDirectory, productOrderCatalog, eventPublisher, couponService);

        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("buyer@example.com", "구매자"));
    }

    // ============================================================
    // 종결 전이 (#3)
    // ============================================================

    @Test
    @DisplayName("refunded=true → Order.markRefunded() 호출 → orders status=refunded")
    void cancel_refundedTrue_callsMarkRefunded() {
        // given
        Order order = createPendingOrderWithItem(1L, 2);
        order.markPaid(); // pending → paid
        mockOrderForUpdate(order);
        mockProductCatalog(1L, 100L);

        // when
        OrderCancellationResult result = cancellationImpl.cancel(
                ORDER_ID, USER_ID, new RefundInfo(true, 20000L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        assertThat(result.orderStatus()).isEqualTo("refunded");
        assertThat(order.getStatus()).isEqualTo("refunded");
    }

    @Test
    @DisplayName("refunded=false → Order.markCancelled() 호출 → orders status=cancelled")
    void cancel_refundedFalse_callsMarkCancelled() {
        // given
        Order order = createPendingOrderWithItem(2L, 1);
        mockOrderForUpdate(order);
        mockProductCatalog(2L, 200L);

        // when
        OrderCancellationResult result = cancellationImpl.cancel(
                ORDER_ID, USER_ID, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        assertThat(result.orderStatus()).isEqualTo("cancelled");
        assertThat(order.getStatus()).isEqualTo("cancelled");
    }

    // ============================================================
    // 재고 복원 순서 검증
    // ============================================================

    @Test
    @DisplayName("재고 복원: variantId 오름차순 + 각 quantity로 increase 호출")
    void cancel_stockRestore_variantIdAscending() {
        // given — variantId: 5, 3 순서로 저장, 복원은 3, 5 순으로 호출되어야 함
        Order order = createOrderWithTwoItems(5L, 2, 3L, 1);
        mockOrderForUpdate(order);
        mockProductCatalog(5L, 500L, 3L, 300L);

        // when
        cancellationImpl.cancel(ORDER_ID, USER_ID, new RefundInfo(false, 0L, "KRW"));

        // then — variantId 오름차순(3→5)
        InOrder inOrder = inOrder(inventoryStockPort);
        inOrder.verify(inventoryStockPort).increase(eq(3L), eq(1), any());
        inOrder.verify(inventoryStockPort).increase(eq(5L), eq(2), any());
    }

    @Test
    @DisplayName("variantId null 항목 skip — 나머지 정상 복원, 이벤트 items에서 제외")
    void cancel_nullVariantId_skipAndLog_otherItemsRestored() {
        // given — variantId null 항목과 정상 항목 혼합
        Order order = createPendingOrder();
        // variantId=null 항목 (삭제된 variant)
        OrderItem nullItem = createOrderItemViaReflection(null, "삭제된상품", 1);
        // variantId=10L 정상 항목
        OrderItem normalItem = createOrderItemViaReflection(10L, "정상상품", 3);
        addItemsToOrder(order, nullItem, normalItem);

        mockOrderForUpdate(order);
        mockProductCatalog(10L, 1000L);

        // when
        OrderCancellationResult result = cancellationImpl.cancel(
                ORDER_ID, USER_ID, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        verify(inventoryStockPort, never()).increase(eq(0L), anyInt(), any()); // null → skip
        verify(inventoryStockPort).increase(eq(10L), eq(3), any()); // 정상 항목은 복원

        // 이벤트 items에 null variant 항목 제외
        ArgumentCaptor<OrderCancelledEvent> eventCaptor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        List<OrderCancelledEvent.Item> items = eventCaptor.getValue().items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).productName()).isEqualTo("정상상품");
    }

    // ============================================================
    // OrderCancelledEvent 필드 검증
    // ============================================================

    @Test
    @DisplayName("OrderCancelledEvent 전 필드 매핑 — memberEmail/memberName=member.spi, items.productId=product.spi")
    void cancel_eventPayload_allFieldsMapped() {
        // given
        Order order = createPendingOrderWithItem(7L, 2);
        order.markPaid(); // refunded=true 경로: paid → refunded 전이 필요
        mockOrderForUpdate(order);
        mockProductCatalog(7L, 700L);
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));

        // when
        cancellationImpl.cancel(ORDER_ID, USER_ID, new RefundInfo(true, 10000L, "KRW"));

        // then
        ArgumentCaptor<OrderCancelledEvent> eventCaptor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        OrderCancelledEvent event = eventCaptor.getValue();

        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.memberId()).isEqualTo(USER_ID);
        assertThat(event.memberEmail()).isEqualTo("test@example.com");
        assertThat(event.memberName()).isEqualTo("테스트유저");
        assertThat(event.refunded()).isTrue();
        assertThat(event.refundedAmount()).isEqualTo(10000L);
        assertThat(event.currency()).isEqualTo("KRW");
        assertThat(event.cancelledAt()).isNotNull();

        assertThat(event.items()).hasSize(1);
        assertThat(event.items().get(0).productId()).isEqualTo(700L);
        assertThat(event.items().get(0).quantity()).isEqualTo(2);
    }

    // ============================================================
    // 멱등 — ALREADY_CANCELLED
    // ============================================================

    @Test
    @DisplayName("이미 cancelled → ALREADY_CANCELLED 멱등 반환(재복원·재발행 없음)")
    void cancel_alreadyCancelled_returnsAlreadyCancelled() {
        // given
        Order order = createPendingOrder();
        order.markCancelled();
        mockOrderForUpdate(order);

        // when
        OrderCancellationResult result = cancellationImpl.cancel(
                ORDER_ID, USER_ID, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.ALREADY_CANCELLED);
        verifyNoInteractions(inventoryStockPort);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 refunded → ALREADY_CANCELLED 멱등 반환")
    void cancel_alreadyRefunded_returnsAlreadyCancelled() {
        // given
        Order order = createPendingOrder();
        order.markPaid();
        order.markRefunded();
        mockOrderForUpdate(order);

        // when
        OrderCancellationResult result = cancellationImpl.cancel(
                ORDER_ID, USER_ID, new RefundInfo(true, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.ALREADY_CANCELLED);
        verifyNoInteractions(inventoryStockPort);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ============================================================
    // REJECTED — 이행단계 (OrderCancellationImpl 자체 분기)
    // ============================================================

    @Test
    @DisplayName("이행단계(preparing) → REJECTED 값 반환")
    void cancel_preparingStatus_returnsRejected() {
        // given
        Order order = createOrderWithStatus("preparing");
        mockOrderForUpdate(order);

        // when
        OrderCancellationResult result = cancellationImpl.cancel(
                ORDER_ID, USER_ID, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.REJECTED);
        verifyNoInteractions(inventoryStockPort);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ============================================================
    // 소유권 404
    // ============================================================

    @Test
    @DisplayName("소유권 불일치 → OrderNotFoundException")
    void cancel_ownershipMismatch_throwsOrderNotFoundException() {
        // given
        Order order = createPendingOrder();
        // userId가 OTHER_USER_ID
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        // order.getUserId()=USER_ID, requesterUserId=OTHER
        long otherUserId = USER_ID + 1;

        // when/then
        assertThatThrownBy(() -> cancellationImpl.cancel(ORDER_ID, otherUserId,
                new RefundInfo(false, 0L, "KRW")))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void mockOrderForUpdate(Order order) {
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private void mockProductCatalog(long variantId, long productId) {
        OrderableVariantSnapshot snap = new OrderableVariantSnapshot(
                variantId, productId, "상품", null, List.of(),
                BigDecimal.valueOf(10000), true, 100, "ON_SALE", true, null);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection())).thenReturn(List.of(snap));
    }

    private void mockProductCatalog(long variantId1, long productId1, long variantId2, long productId2) {
        OrderableVariantSnapshot snap1 = new OrderableVariantSnapshot(
                variantId1, productId1, "상품A", null, List.of(),
                BigDecimal.valueOf(5000), true, 100, "ON_SALE", true, null);
        OrderableVariantSnapshot snap2 = new OrderableVariantSnapshot(
                variantId2, productId2, "상품B", null, List.of(),
                BigDecimal.valueOf(3000), true, 100, "ON_SALE", true, null);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection())).thenReturn(List.of(snap1, snap2));
    }

    private Order createPendingOrder() {
        Order order = Order.create(USER_ID, "ORD-TEST-" + ORDER_ID,
                BigDecimal.valueOf(10000), "수령인", "010", "12345", "서울", null);
        setOrderId(order, ORDER_ID);
        return order;
    }

    private Order createPendingOrderWithItem(long variantId, int quantity) {
        Order order = createPendingOrder();
        OrderItem item = OrderItem.create(variantId, null, "상품", null, BigDecimal.valueOf(5000), quantity);
        order.addItem(item);
        return order;
    }

    private Order createOrderWithTwoItems(long variantId1, int qty1, long variantId2, int qty2) {
        Order order = createPendingOrder();
        order.addItem(OrderItem.create(variantId1, null, "상품A", null, BigDecimal.valueOf(5000), qty1));
        order.addItem(OrderItem.create(variantId2, null, "상품B", null, BigDecimal.valueOf(3000), qty2));
        return order;
    }

    private Order createOrderWithStatus(String status) {
        Order order = createPendingOrder();
        try {
            java.lang.reflect.Field f = Order.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(order, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return order;
    }

    private void setOrderId(Order order, long id) {
        try {
            java.lang.reflect.Field f = Order.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(order, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private OrderItem createOrderItemViaReflection(Long variantId, String productName, int quantity) {
        if (variantId != null) {
            return OrderItem.create(variantId, null, productName, null, BigDecimal.valueOf(5000), quantity);
        }
        // variantId null: OrderItem.create는 long 파라미터라 null 불가 → reflection으로 직접 설정
        OrderItem item = OrderItem.create(1L, null, productName, null, BigDecimal.valueOf(5000), quantity);
        try {
            java.lang.reflect.Field f = OrderItem.class.getDeclaredField("variantId");
            f.setAccessible(true);
            f.set(item, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return item;
    }

    private void addItemsToOrder(Order order, OrderItem... items) {
        for (OrderItem item : items) {
            order.addItem(item);
        }
    }
}
