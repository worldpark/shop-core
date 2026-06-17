package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.PaymentEventResolutionException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.order.spi.OrderPaymentReader.OrderPaymentView;
import com.shop.shop.order.spi.OrderPaymentReader.OrderSnapshotView;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderPaymentReaderImpl 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>getPayableOrder: 자기 주문 OrderPaymentView(scalar) 반환</li>
 *   <li>getPayableOrder: 타인 주문 404 존재 은닉</li>
 *   <li>getPayableOrder: productId 해석 불가 → PaymentEventResolutionException(409)</li>
 *   <li>getPayableOrder: 연락처 해석 불가 → PaymentEventResolutionException(409)</li>
 *   <li>getPayableOrder: variantId null 항목 → PaymentEventResolutionException(409)</li>
 *   <li>getOrderSnapshot: 자기 주문 OrderSnapshotView 반환</li>
 *   <li>getOrderSnapshot: 타인 주문 404</li>
 *   <li>getOrderSnapshot: productId/연락처 해석 불가 주문이어도 409 미발생 (#3)</li>
 *   <li>Entity 미노출 (scalar record만 반환)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentReaderImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductOrderCatalog productOrderCatalog;
    @Mock private MemberDirectory memberDirectory;

    private OrderPaymentReader orderPaymentReader;

    private static final long USER_ID = 1L;
    private static final long ORDER_ID = 100L;
    private static final long VARIANT_ID = 10L;
    private static final long PRODUCT_ID = 5L;
    private static final String ORDER_NUMBER = "ORD-20260608-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);

    @BeforeEach
    void setUp() {
        orderPaymentReader = new OrderPaymentReaderImpl(orderRepository, productOrderCatalog, memberDirectory);
    }

    @Test
    @DisplayName("getPayableOrder: 자기 주문 OrderPaymentView(scalar) 반환")
    void getPayableOrder_ownOrder_returnsOrderPaymentView() {
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        OrderItem item = buildOrderItem(VARIANT_ID, "상품");
        order.addItem(item);

        when(orderRepository.findWithItemsOnlyByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "홍길동"));
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID)));

        OrderPaymentView view = orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID);

        assertThat(view.orderId()).isEqualTo(ORDER_ID);
        assertThat(view.orderNumber()).isEqualTo(ORDER_NUMBER);
        assertThat(view.userId()).isEqualTo(USER_ID);
        assertThat(view.status()).isEqualTo("pending");
        assertThat(view.finalAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(view.currency()).isEqualTo("KRW");
        // Entity 미노출 — record 타입 확인
        assertThat(view).isInstanceOf(OrderPaymentView.class);
    }

    @Test
    @DisplayName("getPayableOrder: 타인 주문 → OrderNotFoundException(404 존재 은닉)")
    void getPayableOrder_otherUsersOrder_throwsOrderNotFoundException() {
        when(orderRepository.findWithItemsOnlyByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("getPayableOrder: productId 해석 불가 → PaymentEventResolutionException(409)")
    void getPayableOrder_productIdResolutionFailed_throwsResolutionException() {
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        order.addItem(buildOrderItem(VARIANT_ID, "상품"));

        when(orderRepository.findWithItemsOnlyByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        // product.spi가 빈 목록 반환 (variant 삭제됨)
        when(productOrderCatalog.getOrderableSnapshots(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID))
                .isInstanceOf(PaymentEventResolutionException.class);
    }

    @Test
    @DisplayName("getPayableOrder: 연락처 해석 불가 → PaymentEventResolutionException(409)")
    void getPayableOrder_contactResolutionFailed_throwsResolutionException() {
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        order.addItem(buildOrderItem(VARIANT_ID, "상품"));

        when(orderRepository.findWithItemsOnlyByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(buildSnapshot(VARIANT_ID, PRODUCT_ID)));
        // member 연락처 해석 실패
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenThrow(new IllegalStateException("연락처 없음"));

        assertThatThrownBy(() -> orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID))
                .isInstanceOf(PaymentEventResolutionException.class);
    }

    @Test
    @DisplayName("getPayableOrder: variantId null 항목 → PaymentEventResolutionException(409)")
    void getPayableOrder_nullVariantId_throwsResolutionException() {
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        // variantId null 항목 (FK SET NULL — variant 삭제됨)
        OrderItem nullVariantItem = buildOrderItemWithNullVariant("상품");
        order.addItem(nullVariantItem);

        when(orderRepository.findWithItemsOnlyByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID))
                .isInstanceOf(PaymentEventResolutionException.class);

        verify(productOrderCatalog, never()).getOrderableSnapshots(anyCollection());
    }

    @Test
    @DisplayName("getOrderSnapshot: 자기 주문 OrderSnapshotView 반환")
    void getOrderSnapshot_ownOrder_returnsOrderSnapshotView() {
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        OrderSnapshotView view = orderPaymentReader.getOrderSnapshot(ORDER_ID, USER_ID);

        assertThat(view.orderId()).isEqualTo(ORDER_ID);
        assertThat(view.status()).isEqualTo("pending");
        assertThat(view).isInstanceOf(OrderSnapshotView.class);
    }

    @Test
    @DisplayName("getOrderSnapshot: 타인 주문 → OrderNotFoundException(404)")
    void getOrderSnapshot_otherUsersOrder_throwsOrderNotFoundException() {
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderPaymentReader.getOrderSnapshot(ORDER_ID, USER_ID))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("getOrderSnapshot: productId/연락처 해석 불가 주문이어도 409 미발생 (#3)")
    void getOrderSnapshot_eventResolutionNotPerformed_noException() {
        Order order = buildOrder(USER_ID, ORDER_NUMBER, AMOUNT, "pending");
        // variantId null 항목이 있어도 getOrderSnapshot은 체크 안 함
        OrderItem nullVariantItem = buildOrderItemWithNullVariant("상품");
        order.addItem(nullVariantItem);

        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        // when — 예외 없음
        OrderSnapshotView view = orderPaymentReader.getOrderSnapshot(ORDER_ID, USER_ID);

        assertThat(view).isNotNull();
        // product.spi/member.spi 미호출
        verify(productOrderCatalog, never()).getOrderableSnapshots(anyCollection());
        verify(memberDirectory, never()).findContactByUserId(USER_ID);
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

    private OrderItem buildOrderItem(long variantId, String productName) {
        return OrderItem.create(variantId, null, productName, null, BigDecimal.valueOf(10000), 1);
    }

    private OrderItem buildOrderItemWithNullVariant(String productName) {
        OrderItem item = OrderItem.create(99L, null, productName, null, BigDecimal.valueOf(10000), 1);
        setField(item, "variantId", null);
        return item;
    }

    private OrderableVariantSnapshot buildSnapshot(long variantId, long productId) {
        return new OrderableVariantSnapshot(
                variantId, productId, "상품", null, List.of(),
                BigDecimal.valueOf(10000), true, 100, "ON_SALE", true, null);
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
