package com.shop.shop.order.controller;

import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipRequest;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.service.OrderFulfillmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 배송 시작 REST API 진입점 (020, C2).
 *
 * <p>base path가 {@link AdminOrderFulfillmentRestController}({@code /api/v1/admin/orders/{orderId}/shipments})와
 * 달라({@code /api/v1/admin/shipments/{shipmentId}/ship}) 별도 컨트롤러로 분리한다(plan §0 C2).
 *
 * <p>비즈니스 로직 없음 — {@link OrderFulfillmentService#ship}에 전적으로 위임한다.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} catch-all 커버.
 * 신규 보안 규칙 불요(plan §0 C6, SecurityConfig 무변경).
 * SELLER/CONSUMER → 403, 비인증 → 401(RestAuthenticationEntryPoint / RestAccessDeniedHandler).
 *
 * <p>레이어: AdminShipmentRestController → OrderFulfillmentService → Repository
 */
@Tag(name = "admin-order", description = "관리자 주문·배송 관리 (ADMIN 전용)")
@RestController
@RequiredArgsConstructor
class AdminShipmentRestController {

    private final OrderFulfillmentService orderFulfillmentService;

    /**
     * 배송 시작.
     * POST /api/v1/admin/shipments/{shipmentId}/ship
     *
     * <p>preparing → shipping 전이 + carrier/trackingNumber/shippedAt 기록 + rollup + ShippingStartedEvent 발행.
     * 이미 shipping인 배송에 재호출 시 멱등 200 반환(본문 무관, 기존 값 유지).
     *
     * @param shipmentId 배송 ID
     * @param req        배송 시작 요청 (carrier/trackingNumber 필수)
     * @return 200 OK + 갱신된 {@link ShipmentResponse}
     */
    @Operation(summary = "배송 시작 (ADMIN)")
    @PostMapping("/api/v1/admin/shipments/{shipmentId}/ship")
    ResponseEntity<ShipmentResponse> ship(
            @PathVariable long shipmentId,
            @Valid @RequestBody ShipRequest req) {
        ShipmentResponse response = orderFulfillmentService.ship(shipmentId, req.carrier(), req.trackingNumber());
        return ResponseEntity.ok(response);
    }

    /**
     * 배송 완료.
     * POST /api/v1/admin/shipments/{shipmentId}/deliver
     *
     * <p>shipping → delivered 전이 + deliveredAt 기록 + 주문 rollup 판정.
     * 이미 delivered인 배송에 재호출 시 멱등 200 반환(상태 불변).
     * 입력 본문 없음 — deliver는 추가 파라미터가 불필요하다.
     *
     * <p>인가: SecurityConfig REST 체인 {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} catch-all 커버.
     * 신규 보안 규칙 불요(plan §0 C9, SecurityConfig 무변경).
     *
     * @param shipmentId 배송 ID
     * @return 200 OK + {@link DeliverResponse}
     */
    @PostMapping("/api/v1/admin/shipments/{shipmentId}/deliver")
    ResponseEntity<DeliverResponse> deliver(@PathVariable long shipmentId) {
        return ResponseEntity.ok(orderFulfillmentService.deliver(shipmentId));
    }
}
