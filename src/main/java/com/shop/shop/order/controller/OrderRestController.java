package com.shop.shop.order.controller;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.order.service.OrderServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 REST 컨트롤러.
 *
 * <p>비즈니스 로직 없음 — {@link OrderServiceResponse} 위임만.
 * 인증 principal은 SecurityContext Authentication(JWT 필터 후 userId).
 *
 * <p>경로: /api/v1/orders — hasRole("CONSUMER") (SecurityConfig REST 체인 설정).
 *
 * <p>POST /api/v1/orders     — 주문 생성 (201 Created)
 * GET  /api/v1/orders     — 내 주문 목록 (200 OK)
 * GET  /api/v1/orders/{id} — 내 주문 상세 (200 OK)
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderRestController {

    private final OrderServiceResponse orderServiceResponse;

    /**
     * 주문 생성.
     *
     * <p>@Valid 배송지 검증 실패 → 400(MethodArgumentNotValid → RestExceptionHandler).
     * 빈 장바구니 → 400(EmptyCartException).
     * 재고 부족/구매 불가 → 409(InsufficientStockException/ProductNotPurchasableForOrderException).
     *
     * @param authentication JWT 인증 정보 (principal=userId)
     * @param request        배송지 정보
     * @return 201 + 생성된 주문 상세
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            Authentication authentication,
            @Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderServiceResponse.createOrder(authentication, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 주문 목록 조회 (최신순).
     *
     * @param authentication JWT 인증 정보
     * @param pageable       페이지 요청 (기본: page=0, size=10, created_at DESC)
     * @return 200 + 주문 요약 목록 페이지
     */
    @GetMapping
    public ResponseEntity<PageResponse<OrderSummaryResponse>> getMyOrders(
            Authentication authentication,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PageResponse<OrderSummaryResponse> response = orderServiceResponse.getMyOrders(authentication, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 내 주문 상세 조회.
     *
     * <p>타인/미존재 주문 → 404 존재 은닉(OrderNotFoundException → RestExceptionHandler).
     *
     * @param authentication JWT 인증 정보
     * @param orderId        주문 ID
     * @return 200 + 주문 상세
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getMyOrder(
            Authentication authentication,
            @PathVariable long orderId) {
        OrderResponse response = orderServiceResponse.getMyOrder(authentication, orderId);
        return ResponseEntity.ok(response);
    }
}
