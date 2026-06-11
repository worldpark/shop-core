package com.shop.shop.order.service;

import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.common.dto.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 주문 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — OrderService에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다.
 *
 * <p>REST principal: JWT 필터 후 {@code (long) authentication.getPrincipal()} — CartServiceResponse 선례.
 *
 * <p>레이어: OrderRestController → OrderServiceResponse → OrderService → Repository
 */
@Service
@RequiredArgsConstructor
public class OrderServiceResponse {

    private final OrderService orderService;
    private final OrderDtoMapper dtoMapper;

    /**
     * 주문 생성 — REST 전용.
     *
     * <p>(long) auth.getPrincipal() 추출 → OrderService.placeOrder 위임 → 상세 조회 → OrderResponse 변환.
     *
     * @param authentication JWT SecurityContext
     * @param request        배송지 정보
     * @return 생성된 주문 상세 (ownerId/Entity 미노출)
     */
    public OrderResponse createOrder(Authentication authentication, OrderCreateRequest request) {
        long userId = (long) authentication.getPrincipal();
        OrderService.OrderResult result = orderService.placeOrder(userId, request);
        OrderService.OrderDetail detail = orderService.getMyOrder(userId, result.orderId());
        return dtoMapper.toOrderResponse(detail, List.of());
    }

    /**
     * 내 주문 목록 조회 — REST 전용.
     *
     * @param authentication JWT SecurityContext
     * @param pageable       페이지 요청
     * @return 최신순 주문 요약 페이지
     */
    public PageResponse<OrderSummaryResponse> getMyOrders(Authentication authentication, Pageable pageable) {
        long userId = (long) authentication.getPrincipal();
        Page<OrderService.OrderSummary> summaries = orderService.getMyOrders(userId, pageable);
        Page<OrderSummaryResponse> responses = summaries.map(dtoMapper::toOrderSummaryResponse);
        return PageResponse.of(responses);
    }

    /**
     * 내 주문 상세 조회 — REST 전용.
     *
     * <p>타인/미존재 주문 → OrderNotFoundException(404 존재 은닉).
     *
     * @param authentication JWT SecurityContext
     * @param orderId        주문 ID
     * @return 주문 상세 (ownerId/Entity 미노출)
     */
    public OrderResponse getMyOrder(Authentication authentication, long orderId) {
        long userId = (long) authentication.getPrincipal();
        OrderService.OrderDetail detail = orderService.getMyOrder(userId, orderId);
        return dtoMapper.toOrderResponse(detail, List.of());
    }
}
