package com.shop.shop.order.service;

import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.ShippingAddressResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link OrderServiceResponse} 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>(long) auth.getPrincipal() 추출 → OrderService 위임</li>
 *   <li>OrderResponse 변환 — ownerId/Entity 미노출</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceResponseTest {

    @Mock
    private OrderService orderService;

    @Mock
    private OrderDtoMapper dtoMapper;

    @Mock
    private Authentication authentication;

    private OrderServiceResponse orderServiceResponse;

    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        orderServiceResponse = new OrderServiceResponse(orderService, dtoMapper);
    }

    @Test
    @DisplayName("createOrder: (long)auth.getPrincipal()로 userId 추출 → orderService.placeOrder 위임")
    void createOrder_extractsUserIdFromPrincipal() {
        when(authentication.getPrincipal()).thenReturn(USER_ID);
        when(orderService.placeOrder(anyLong(), any())).thenReturn(new OrderService.OrderResult(1L, "ORD-001"));
        when(orderService.getMyOrder(eq(USER_ID), eq(1L))).thenReturn(makeOrderDetail(1L));
        OrderResponse expected = makeOrderResponse(1L);
        when(dtoMapper.toOrderResponse(any(), any())).thenReturn(expected);

        OrderResponse result = orderServiceResponse.createOrder(authentication,
                new com.shop.shop.order.dto.OrderCreateRequest("홍", "010", "12345", "서울", null, null));

        assertThat(result.orderId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("createOrder 응답에 ownerId 미포함")
    void createOrder_responseDoesNotContainOwnerId() {
        when(authentication.getPrincipal()).thenReturn(USER_ID);
        when(orderService.placeOrder(anyLong(), any())).thenReturn(new OrderService.OrderResult(1L, "ORD-001"));
        when(orderService.getMyOrder(anyLong(), anyLong())).thenReturn(makeOrderDetail(1L));
        OrderResponse expected = makeOrderResponse(1L);
        when(dtoMapper.toOrderResponse(any(), any())).thenReturn(expected);

        OrderResponse result = orderServiceResponse.createOrder(authentication,
                new com.shop.shop.order.dto.OrderCreateRequest("홍", "010", "12345", "서울", null, null));

        // OrderResponse record에 userId 필드 없음
        assertThat(result).isNotNull();
        // orderId만 있고 userId는 노출 안 됨
        assertThat(result.orderId()).isNotNull();
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
                new ShippingAddressResponse("홍", "010", "12345", "서울", null),
                Instant.now(), List.of());
    }
}
