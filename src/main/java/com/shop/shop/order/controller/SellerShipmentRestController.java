package com.shop.shop.order.controller;

import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipRequest;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 배송 시작·완료 REST API 진입점.
 *
 * <p>POST /api/v1/seller/shipments/{shipmentId}/ship
 * POST /api/v1/seller/shipments/{shipmentId}/deliver
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/seller/**} → {@code hasRole("SELLER")} 기존 커버.
 * 소유권 검사(shipment.seller_id == sellerId)는 facade 구현체 내부에서 스칼라 projection으로 수행한다
 * (엔티티 선적재 금지 — stale-read 가드, plan §1.2).
 * 타 판매자/admin 생성 배송 조작 시도 → 404(존재 은닉).
 *
 * <p>레이어: SellerShipmentRestController → SellerFulfillmentFacade(spi) → 구현체(service)
 */
@Tag(name = "seller-order", description = "판매자 주문·배송 관리 (SELLER 이상)")
@RestController
@RequiredArgsConstructor
class SellerShipmentRestController {

    private final SellerFulfillmentFacade sellerFulfillmentFacade;

    /**
     * 판매자 배송 시작.
     * POST /api/v1/seller/shipments/{shipmentId}/ship
     *
     * <p>이 판매자 소유(seller_id == sellerId) preparing 배송을 shipping으로 전이한다.
     * ShippingStartedEvent 발행(기존 경로 재사용).
     *
     * @param shipmentId 배송 ID
     * @param req        배송 시작 요청 (carrier/trackingNumber 필수)
     * @param auth       SecurityContext 인증 객체
     * @return 200 OK + 갱신된 {@link ShipmentResponse}
     */
    @Operation(summary = "판매자 배송 시작 (SELLER 이상)")
    @PostMapping("/api/v1/seller/shipments/{shipmentId}/ship")
    ResponseEntity<ShipmentResponse> ship(
            @PathVariable long shipmentId,
            @Valid @RequestBody ShipRequest req,
            Authentication auth) {

        String actorEmail = auth.getName();
        ShipmentResponse response = sellerFulfillmentFacade.ship(actorEmail, shipmentId,
                req.carrier(), req.trackingNumber());
        return ResponseEntity.ok(response);
    }

    /**
     * 판매자 배송 완료.
     * POST /api/v1/seller/shipments/{shipmentId}/deliver
     *
     * <p>이 판매자 소유(seller_id == sellerId) shipping 배송을 delivered로 전이한다.
     * 멀티셀러 주문 deliver-when-all rollup 재사용.
     *
     * @param shipmentId 배송 ID
     * @param auth       SecurityContext 인증 객체
     * @return 200 OK + {@link DeliverResponse}
     */
    @PostMapping("/api/v1/seller/shipments/{shipmentId}/deliver")
    ResponseEntity<DeliverResponse> deliver(
            @PathVariable long shipmentId,
            Authentication auth) {

        String actorEmail = auth.getName();
        return ResponseEntity.ok(sellerFulfillmentFacade.deliver(actorEmail, shipmentId));
    }
}
