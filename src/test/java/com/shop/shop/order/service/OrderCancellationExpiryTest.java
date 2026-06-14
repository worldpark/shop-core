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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * OrderCancellationImpl.cancelByExpiry 단위 테스트 (022 신규, Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>cancelByExpiry → 소유권 검증 없음(userId 미사용) → doCancel 코어 공유(R2)</li>
 *   <li>pending → markCancelled(→cancelled) + 재고 복원(variantId 오름차순) + OrderCancelledEvent(refunded=false)</li>
 *   <li>variantId null 항목 skip+log, 나머지 정상 복원</li>
 *   <li>ALREADY_CANCELLED 멱등: 이미 cancelled → 재복원·재발행 없음</li>
 *   <li>미존재 주문 → OrderNotFoundException</li>
 *   <li>코어 공유(R2): cancel(userId)와 cancelByExpiry 모두 동일 전이/복원/이벤트 수행</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCancellationExpiryTest {

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

    private static final long ORDER_ID = 20L;
    private static final long USER_ID = 5L;

    @BeforeEach
    void setUp() {
        cancellationImpl = new OrderCancellationImpl(
                orderRepository, inventoryStockPort, memberDirectory, productOrderCatalog, eventPublisher, couponService);

        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("buyer@example.com", "구매자"));
    }

    // ============================================================
    // 소유권 미검사 + 종결 전이
    // ============================================================

    @Test
    @DisplayName("cancelByExpiry: pending → markCancelled(→cancelled), 소유권 검증 없음")
    void cancelByExpiry_pending_marksCancelled_noOwnershipCheck() {
        // given
        Order order = createPendingOrderWithItem(1L, 2);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        mockProductCatalog(1L, 100L);

        // when — userId 인자 없이 호출 (소유권 미검사)
        OrderCancellationResult result = cancellationImpl.cancelByExpiry(ORDER_ID);

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        assertThat(result.orderStatus()).isEqualTo("cancelled");
        assertThat(order.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("cancelByExpiry: 소유권이 다른 userId여도 정상 처리(소유권 검증 없음)")
    void cancelByExpiry_differentUserId_noException() {
        // given — 주문 소유자가 USER_ID이지만 cancelByExpiry는 userId 없이 호출
        Order order = createPendingOrderWithItem(2L, 1);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        mockProductCatalog(2L, 200L);

        // when — 소유권 검증이 없으므로 예외 없음
        OrderCancellationResult result = cancellationImpl.cancelByExpiry(ORDER_ID);

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
    }

    // ============================================================
    // refunded=false 고정 (환불 없음)
    // ============================================================

    @Test
    @DisplayName("cancelByExpiry: RefundInfo(refunded=false, refundedAmount=0, currency=KRW) 고정 → 이벤트 refunded=false")
    void cancelByExpiry_refundedFalse_fixedRefundInfo() {
        // given
        Order order = createPendingOrderWithItem(3L, 1);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        mockProductCatalog(3L, 300L);

        // when
        cancellationImpl.cancelByExpiry(ORDER_ID);

        // then — 이벤트의 refunded=false, refundedAmount=0, currency=KRW
        ArgumentCaptor<OrderCancelledEvent> eventCaptor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        OrderCancelledEvent event = eventCaptor.getValue();
        assertThat(event.refunded()).isFalse();
        assertThat(event.refundedAmount()).isEqualTo(0L);
        assertThat(event.currency()).isEqualTo("KRW");
    }

    // ============================================================
    // 재고 복원 (variantId 오름차순)
    // ============================================================

    @Test
    @DisplayName("cancelByExpiry: 재고 복원 variantId 오름차순 (doCancel 코어 공유 — R2)")
    void cancelByExpiry_stockRestore_variantIdAscending() {
        // given — variantId: 10, 3 순서로 저장, 복원은 3, 10 순서
        Order order = createPendingOrder();
        order.addItem(OrderItem.create(10L, "상품A", null, BigDecimal.valueOf(5000), 2));
        order.addItem(OrderItem.create(3L, "상품B", null, BigDecimal.valueOf(3000), 1));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        mockProductCatalog(10L, 1000L, 3L, 300L);

        // when
        cancellationImpl.cancelByExpiry(ORDER_ID);

        // then — variantId 오름차순(3→10)
        InOrder inOrder = inOrder(inventoryStockPort);
        inOrder.verify(inventoryStockPort).increase(3L, 1);
        inOrder.verify(inventoryStockPort).increase(10L, 2);
    }

    // ============================================================
    // variantId null 항목 skip
    // ============================================================

    @Test
    @DisplayName("cancelByExpiry: variantId null 항목 skip, 정상 항목 복원")
    void cancelByExpiry_nullVariantId_skipAndRestore() {
        // given
        Order order = createPendingOrder();
        OrderItem nullItem = createNullVariantItem("삭제상품", 1);
        OrderItem normalItem = OrderItem.create(5L, "정상상품", null, BigDecimal.valueOf(5000), 3);
        order.addItem(nullItem);
        order.addItem(normalItem);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        mockProductCatalog(5L, 500L);

        // when
        OrderCancellationResult result = cancellationImpl.cancelByExpiry(ORDER_ID);

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        verify(inventoryStockPort, never()).increase(eq(0L), anyInt());
        verify(inventoryStockPort).increase(5L, 3);
    }

    // ============================================================
    // ALREADY_CANCELLED 멱등
    // ============================================================

    @Test
    @DisplayName("cancelByExpiry: 이미 cancelled → ALREADY_CANCELLED 멱등 반환 (재복원·재발행 없음)")
    void cancelByExpiry_alreadyCancelled_returnsAlreadyCancelled() {
        // given
        Order order = createPendingOrder();
        order.markCancelled();
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        // when
        OrderCancellationResult result = cancellationImpl.cancelByExpiry(ORDER_ID);

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.ALREADY_CANCELLED);
        verifyNoInteractions(inventoryStockPort);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ============================================================
    // 미존재 주문
    // ============================================================

    @Test
    @DisplayName("cancelByExpiry: 미존재 주문 → OrderNotFoundException")
    void cancelByExpiry_orderNotFound_throwsException() {
        // given
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        // when/then
        assertThatThrownBy(() -> cancellationImpl.cancelByExpiry(ORDER_ID))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ============================================================
    // 코어 공유(R2): cancel(userId)와 cancelByExpiry가 동일 코어 사용
    // ============================================================

    @Test
    @DisplayName("코어 공유(R2): cancel(userId) — 소유권 검증 O + 동일 재고복원·이벤트 수행")
    void cancel_withOwnership_sameCoreBehavior() {
        // given — cancel(userId)도 동일 doCancel 코어 사용
        Order order = createPendingOrderWithItem(7L, 2);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        mockProductCatalog(7L, 700L);

        // when
        OrderCancellationResult result = cancellationImpl.cancel(ORDER_ID, USER_ID,
                new RefundInfo(false, 0L, "KRW"));

        // then — 동일 코어: CANCELLED + 재고 복원 + 이벤트 발행
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        assertThat(order.getStatus()).isEqualTo("cancelled");
        verify(inventoryStockPort).increase(7L, 2);

        ArgumentCaptor<OrderCancelledEvent> eventCaptor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().refunded()).isFalse();
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Order createPendingOrder() {
        Order order = Order.create(USER_ID, "ORD-EXP-" + ORDER_ID,
                BigDecimal.valueOf(10000), "수령인", "010", "12345", "서울", null);
        setOrderId(order, ORDER_ID);
        return order;
    }

    private Order createPendingOrderWithItem(long variantId, int quantity) {
        Order order = createPendingOrder();
        order.addItem(OrderItem.create(variantId, "상품", null, BigDecimal.valueOf(5000), quantity));
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

    private OrderItem createNullVariantItem(String productName, int quantity) {
        OrderItem item = OrderItem.create(1L, productName, null, BigDecimal.valueOf(5000), quantity);
        try {
            java.lang.reflect.Field f = OrderItem.class.getDeclaredField("variantId");
            f.setAccessible(true);
            f.set(item, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return item;
    }

    private void mockProductCatalog(long variantId, long productId) {
        OrderableVariantSnapshot snap = new OrderableVariantSnapshot(
                variantId, productId, "상품", null, List.of(),
                BigDecimal.valueOf(10000), true, 100, "ON_SALE", true);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection())).thenReturn(List.of(snap));
    }

    private void mockProductCatalog(long variantId1, long productId1, long variantId2, long productId2) {
        OrderableVariantSnapshot snap1 = new OrderableVariantSnapshot(
                variantId1, productId1, "상품A", null, List.of(),
                BigDecimal.valueOf(5000), true, 100, "ON_SALE", true);
        OrderableVariantSnapshot snap2 = new OrderableVariantSnapshot(
                variantId2, productId2, "상품B", null, List.of(),
                BigDecimal.valueOf(3000), true, 100, "ON_SALE", true);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection())).thenReturn(List.of(snap1, snap2));
    }
}
