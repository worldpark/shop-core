package com.shop.shop.payment.controller;

import com.shop.shop.payment.dto.OrderCancelResponse;
import com.shop.shop.payment.service.PaymentServiceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 취소 REST Controller (payment 모듈 소유 — #1).
 *
 * <p>경로: {@code POST /api/v1/orders/{orderId}/cancel}.
 *
 * <p><b>별도 컨트롤러로 분리한 이유(#1)</b>:
 * 기존 {@link PaymentRestController}의 클래스 매핑이 {@code /api/v1/orders/{orderId}/payment}라,
 * 거기에 cancel 메서드를 추가하면 Spring MVC가 클래스+메서드 경로를 결합해
 * {@code .../payment/.../cancel}로 경로가 깨진다(절대경로 메서드 매핑 불가).
 * 따라서 클래스 매핑 자체를 {@code /api/v1/orders/{orderId}/cancel}로 분리한다.
 * 기존 {@link PaymentRestController} 무변경(회귀 0).
 *
 * <p><b>order/controller에 두지 않는 이유</b>:
 * {@code order → payment} 순환이 되어 {@code ModularityTests}가 깨진다(#1).
 * 취소 오케스트레이션은 payment 모듈이 소유한다(016/017 대칭).
 *
 * <p>Security REST 체인: {@code /api/v1/orders/{asterisk}/cancel} hasRole("CONSUMER") 명시 추가됨.
 * {@code /api/v1/orders/..} hasRole("CONSUMER")가 이미 덮지만 의도 명시 + 회귀 방지.
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}/cancel")
@RequiredArgsConstructor
public class PaymentCancellationRestController {

    private final PaymentServiceResponse paymentServiceResponse;

    /**
     * 주문 취소 처리.
     *
     * <p>성공: 200 + {@link OrderCancelResponse}(취소 후 주문 상태·환불 여부/금액).
     * 멱등 재취소: 200(동일 결과).
     * 이행단계(preparing/shipping/delivered): 409 + ErrorResponse.
     * 타인/미존재 주문: 404 존재 은닉.
     *
     * @param orderId 주문 ID
     * @param auth    JWT 인증 (principal = userId(long))
     * @return 취소 결과 200
     */
    @PostMapping
    public ResponseEntity<OrderCancelResponse> cancel(
            @PathVariable long orderId,
            Authentication auth
    ) {
        OrderCancelResponse response = paymentServiceResponse.cancel(auth, orderId);
        return ResponseEntity.ok(response);
    }
}
