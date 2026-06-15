package com.shop.shop.order.service;

import com.shop.shop.common.exception.AmountConversionException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.event.OrderCompletedEvent;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.order.spi.OrderConfirmation.OrderConfirmationResult;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderConfirmationImpl 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>pending → paid 전이 + CONFIRMED + OrderCompletedEvent 발행</li>
 *   <li>이미 paid → ALREADY_CONFIRMED 멱등 반환 (eventPublished=false, 재발행 없음)</li>
 *   <li>기타 상태 → REJECTED + rejectedReason 포함 (예외 대신 값 반환)</li>
 *   <li>금액 불일치 → REJECTED + rejectedReason 포함 (예외 대신 값 반환)</li>
 *   <li>OrderCompletedEvent 전 필드 매핑 (ArgumentCaptor)</li>
 *   <li>productName/quantity/unitPrice가 order_items 스냅샷 출처 (P4)</li>
 *   <li>금액 longValueExact 성공 / 소수부 있으면 AmountConversionException (P3)</li>
 *   <li>publishEvent 정확히 1회</li>
 *   <li>소유권 불일치 → OrderNotFoundException(404) throw 유지</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderConfirmationImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private MemberDirectory memberDirectory;
    @Mock private ProductOrderCatalog productOrderCatalog;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EntityManager entityManager;

    private OrderConfirmation orderConfirmation;

    private static final long USER_ID = 1L;
    private static final long ORDER_ID = 100L;
    private static final long VARIANT_ID = 10L;
    private static final long PRODUCT_ID = 5L;
    private static final String ORDER_NUMBER = "ORD-20260608-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);

    @BeforeEach
    void setUp() {
        OrderConfirmationImpl impl = new OrderConfirmationImpl(
                orderRepository, memberDirectory, productOrderCatalog, eventPublisher);
        // @Autowired @Lazy EntityManager은 Spring이 주입하므로 단위테스트에서는 리플렉션으로 설정.
        // refresh()는 1-A(033) 핵심 기능이지만 단위테스트에서는 DB가 없으므로 no-op 스텁.
        doNothing().when(entityManager).refresh(any());
        setField(impl, "entityManager", entityManager);
        orderConfirmation = impl;
    }

    @Test
    @DisplayName("pending → paid 전이 + CONFIRMED + OrderCompletedEvent 발행")
    void confirmPaid_pendingToPaid_publishesEvent() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        OrderItem item = buildOrderItem(VARIANT_ID, "무선 키보드", BigDecimal.valueOf(10000), 1);
        order.addItem(item);

        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "홍길동"));
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID, "무선 키보드")));

        // when
        OrderConfirmationResult result = orderConfirmation.confirmPaid(ORDER_ID, USER_ID, AMOUNT);

        // then
        assertThat(result.outcome()).isEqualTo(OrderConfirmation.Outcome.CONFIRMED);
        assertThat(result.eventPublished()).isTrue();
        assertThat(result.rejectedReason()).isNull();
        verify(eventPublisher, times(1)).publishEvent(any(OrderCompletedEvent.class));
    }

    @Test
    @DisplayName("OrderCompletedEvent 전 필드 매핑 (ArgumentCaptor)")
    void confirmPaid_eventPayload_allFieldsMapped() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, BigDecimal.valueOf(10000), "pending");
        OrderItem item = buildOrderItem(VARIANT_ID, "무선 키보드", BigDecimal.valueOf(10000), 1);
        order.addItem(item);

        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "홍길동"));
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID, "무선 키보드")));

        ArgumentCaptor<OrderCompletedEvent> captor = ArgumentCaptor.forClass(OrderCompletedEvent.class);

        // when
        orderConfirmation.confirmPaid(ORDER_ID, USER_ID, BigDecimal.valueOf(10000));

        // then — 전 필드 검증
        verify(eventPublisher).publishEvent(captor.capture());
        OrderCompletedEvent event = captor.getValue();

        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.orderNumber()).isEqualTo(ORDER_NUMBER);
        assertThat(event.memberId()).isEqualTo(USER_ID);
        assertThat(event.memberEmail()).isEqualTo("user@example.com");
        assertThat(event.memberName()).isEqualTo("홍길동");
        assertThat(event.totalAmount()).isEqualTo(10000L);
        assertThat(event.currency()).isEqualTo("KRW");
        assertThat(event.orderedAt()).isNotNull();

        assertThat(event.items()).hasSize(1);
        OrderCompletedEvent.Item eventItem = event.items().get(0);
        assertThat(eventItem.productId()).isEqualTo(PRODUCT_ID);
        // P4: productName/quantity/unitPrice는 order_items 스냅샷 출처
        assertThat(eventItem.productName()).isEqualTo("무선 키보드");
        assertThat(eventItem.quantity()).isEqualTo(1);
        assertThat(eventItem.unitPrice()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("productName/quantity/unitPrice는 order_items 스냅샷 출처 (P4) — product 변경 무영향")
    void confirmPaid_eventItems_fromOrderItemsSnapshot() {
        // given — 주문 시점 상품명 "구 상품명", 현재 product 이름은 다름
        Order order = buildOrder(USER_ID, ORDER_NUMBER, BigDecimal.valueOf(50000), "pending");
        OrderItem item = buildOrderItem(VARIANT_ID, "구 상품명", BigDecimal.valueOf(50000), 1);
        order.addItem(item);

        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "홍길동"));
        // product.spi는 현재 이름("새 상품명")을 반환하지만 이벤트는 스냅샷 사용
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID, "새 상품명")));

        ArgumentCaptor<OrderCompletedEvent> captor = ArgumentCaptor.forClass(OrderCompletedEvent.class);

        // when
        orderConfirmation.confirmPaid(ORDER_ID, USER_ID, BigDecimal.valueOf(50000));

        // then — 이벤트 productName은 스냅샷("구 상품명"), product 현재 이름("새 상품명") 아님
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().items().get(0).productName()).isEqualTo("구 상품명");
    }

    @Test
    @DisplayName("이미 paid → ALREADY_CONFIRMED 멱등 반환 (eventPublished=false, 재발행 없음)")
    void confirmPaid_alreadyPaid_idempotentReturn() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "paid");
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        // when
        OrderConfirmationResult result = orderConfirmation.confirmPaid(ORDER_ID, USER_ID, AMOUNT);

        // then
        assertThat(result.outcome()).isEqualTo(OrderConfirmation.Outcome.ALREADY_CONFIRMED);
        assertThat(result.eventPublished()).isFalse();
        assertThat(result.rejectedReason()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("pending 외 상태 → REJECTED + rejectedReason 포함 (예외 대신 값 반환)")
    void confirmPaid_nonPendingNonPaid_returnsRejected() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "cancelled");
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        // when
        OrderConfirmationResult result = orderConfirmation.confirmPaid(ORDER_ID, USER_ID, AMOUNT);

        // then
        assertThat(result.outcome()).isEqualTo(OrderConfirmation.Outcome.REJECTED);
        assertThat(result.eventPublished()).isFalse();
        assertThat(result.rejectedReason()).isNotBlank();
        assertThat(result.rejectedReason()).contains("cancelled");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("금액 불일치 → REJECTED + rejectedReason 포함 (예외 대신 값 반환)")
    void confirmPaid_amountMismatch_returnsRejected() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        // when
        OrderConfirmationResult result = orderConfirmation.confirmPaid(ORDER_ID, USER_ID, BigDecimal.valueOf(9999));

        // then
        assertThat(result.outcome()).isEqualTo(OrderConfirmation.Outcome.REJECTED);
        assertThat(result.eventPublished()).isFalse();
        assertThat(result.rejectedReason()).isNotBlank();
        assertThat(result.rejectedReason()).contains("금액");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("금액 longValueExact 성공 — .00 BigDecimal")
    void confirmPaid_amountLongValueExact_success() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, new BigDecimal("10000.00"), "pending");
        OrderItem item = buildOrderItem(VARIANT_ID, "상품", new BigDecimal("10000.00"), 1);
        order.addItem(item);

        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "홍길동"));
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID, "상품")));

        // when & then — 예외 없음
        orderConfirmation.confirmPaid(ORDER_ID, USER_ID, new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("금액 소수부 있으면 AmountConversionException (P3)")
    void confirmPaid_amountWithDecimal_throwsAmountConversionException() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, new BigDecimal("10000.50"), "pending");
        OrderItem item = buildOrderItem(VARIANT_ID, "상품", new BigDecimal("10000.50"), 1);
        order.addItem(item);

        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "홍길동"));
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID, "상품")));

        // when & then
        assertThatThrownBy(() -> orderConfirmation.confirmPaid(ORDER_ID, USER_ID, new BigDecimal("10000.50")))
                .isInstanceOf(AmountConversionException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("타인 주문 → OrderNotFoundException(404)")
    void confirmPaid_otherUsersOrder_throwsOrderNotFoundException() {
        // given
        Order order = buildOrder(99L, ORDER_NUMBER, AMOUNT, "pending"); // 다른 userId
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> orderConfirmation.confirmPaid(ORDER_ID, USER_ID, AMOUNT))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("publishEvent 정확히 1회")
    void confirmPaid_publishEventExactlyOnce() {
        // given
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        OrderItem item = buildOrderItem(VARIANT_ID, "상품", AMOUNT, 1);
        order.addItem(item);

        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "홍길동"));
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID, "상품")));

        // when
        orderConfirmation.confirmPaid(ORDER_ID, USER_ID, AMOUNT);

        // then
        verify(eventPublisher, times(1)).publishEvent(any(OrderCompletedEvent.class));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Order buildOrder(long userId, String orderNumber, BigDecimal amount, String status) {
        Order order = Order.create(userId, orderNumber, amount,
                "수령인", "010-1234-5678", "12345", "서울시", null);
        setField(order, "id", ORDER_ID);
        setField(order, "status", status);
        return order;
    }

    private OrderItem buildOrderItem(long variantId, String productName, BigDecimal unitPrice, int quantity) {
        return OrderItem.create(variantId, productName, null, unitPrice, quantity);
    }

    private OrderableVariantSnapshot buildSnapshot(long variantId, long productId, String productName) {
        return new OrderableVariantSnapshot(
                variantId, productId, productName, null, List.of(),
                BigDecimal.valueOf(10000), true, 100, "ON_SALE", true);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("필드 설정 실패: " + fieldName, e);
        }
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
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
