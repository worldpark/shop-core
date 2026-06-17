package com.shop.shop.order.controller;

import com.shop.shop.order.dto.CreateShipmentRequest;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 판매자 배송 생성 REST API 진입점.
 *
 * <p>POST /api/v1/seller/orders/{orderId}/shipments
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/seller/**} → {@code hasRole("SELLER")} 기존 커버.
 * 소유권 검사는 {@link SellerFulfillmentFacade} 구현체 내부(service 레이어)에서 수행한다.
 *
 * <p>레이어: SellerOrderFulfillmentRestController → SellerFulfillmentFacade(spi) → 구현체(service)
 * web→member.spi 직접 호출 금지(facade 내부 email→sellerId 해석).
 */
@RestController
@RequestMapping("/api/v1/seller/orders/{orderId}/shipments")
@RequiredArgsConstructor
public class SellerOrderFulfillmentRestController {

    private final SellerFulfillmentFacade sellerFulfillmentFacade;

    /**
     * 판매자 배송 생성.
     * POST /api/v1/seller/orders/{orderId}/shipments
     *
     * <p>판매자 자기 소유(owner_id) 미발송 항목으로만 배송 1건을 생성한다.
     * {@code orderItemIds} 생략(null/빈 목록) 시 그 판매자 소유 미발송 항목 전부.
     * 타 판매자/미존재 항목 지정 시 404(존재 은닉).
     *
     * @param orderId 주문 ID
     * @param req     배송 생성 요청 (optional body — orderItemIds)
     * @param auth    SecurityContext 인증 객체 (email 추출)
     * @return 201 Created + {@link ShipmentResponse}
     */
    @PostMapping
    public ResponseEntity<ShipmentResponse> create(
            @PathVariable long orderId,
            @RequestBody(required = false) CreateShipmentRequest req,
            Authentication auth) {

        String actorEmail = auth.getName();
        List<Long> orderItemIds = (req != null) ? req.orderItemIds() : null;
        ShipmentResponse response = sellerFulfillmentFacade.createShipment(actorEmail, orderId, orderItemIds);
        return ResponseEntity.status(201).body(response);
    }
}
