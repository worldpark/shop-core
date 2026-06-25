package com.shop.shop.order.controller;

import com.shop.shop.order.dto.CreateShipmentRequest;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.service.OrderFulfillmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 배송 REST API 진입점.
 *
 * <p>비즈니스 로직 없음 — {@link OrderFulfillmentService}에 전적으로 위임한다.
 *
 * <p>인가: SecurityConfig REST 체인에서 {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} 보장.
 * SELLER / CONSUMER → 403, 비인증 → 401 (RestAuthenticationEntryPoint / RestAccessDeniedHandler).
 *
 * <p>관리자 경로라 소유권 검사 불요 (ROLE_ADMIN 전역 권한 — plan §1.4).
 * Repository 직접 주입 금지 — 서비스 레이어 경유.
 *
 * <p>레이어: AdminOrderFulfillmentRestController → OrderFulfillmentService → Repository
 */
@Tag(name = "admin-order", description = "관리자 주문·배송 관리 (ADMIN 전용)")
@RestController
@RequestMapping("/api/v1/admin/orders/{orderId}/shipments")
@RequiredArgsConstructor
public class AdminOrderFulfillmentRestController {

    private final OrderFulfillmentService orderFulfillmentService;

    /**
     * 배송 생성.
     * POST /api/v1/admin/orders/{orderId}/shipments
     *
     * <p>{@code orderItemIds} 생략(null/빈 목록) 시 미발송 항목 전부로 배송 1건을 생성한다.
     * 지정 시 해당 항목만 대상으로 한다.
     *
     * @param orderId 주문 ID
     * @param req     배송 생성 요청 (optional body)
     * @return 201 Created + {@link ShipmentResponse}
     */
    @Operation(summary = "배송 생성 (ADMIN)")
    @PostMapping
    public ResponseEntity<ShipmentResponse> create(
            @PathVariable long orderId,
            @RequestBody(required = false) CreateShipmentRequest req) {

        List<Long> orderItemIds = (req != null) ? req.orderItemIds() : null;
        ShipmentResponse response = orderFulfillmentService.createShipment(orderId, orderItemIds);
        return ResponseEntity.status(201).body(response);
    }

    /**
     * 배송 목록 조회.
     * GET /api/v1/admin/orders/{orderId}/shipments
     *
     * <p>미존재 주문이어도 빈 목록을 반환한다 (404는 생성 경로에서만).
     *
     * @param orderId 주문 ID
     * @return 200 OK + 배송 목록
     */
    @GetMapping
    public ResponseEntity<List<ShipmentResponse>> list(@PathVariable long orderId) {
        List<ShipmentResponse> response = orderFulfillmentService.getShipments(orderId);
        return ResponseEntity.ok(response);
    }
}
