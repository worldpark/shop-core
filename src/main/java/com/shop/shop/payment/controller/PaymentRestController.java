package com.shop.shop.payment.controller;

import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.service.PaymentServiceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 REST Controller.
 *
 * <p>경로: {@code /api/v1/orders/{orderId}/payment}.
 * Security REST 체인(@Order(1))이 {@code /api/v1/orders/**} hasRole("CONSUMER")로 이미 보호.
 * SecurityConfig에 결제 하위 경로 전용 matcher도 명시 추가됨(Task 016 요구사항).
 *
 * <p>비즈니스 로직 없음 — {@link PaymentServiceResponse} 위임.
 * Authentication principal = userId(long) (JWT 필터 주입).
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}/payment")
@RequiredArgsConstructor
public class PaymentRestController {

    private final PaymentServiceResponse paymentServiceResponse;

    /**
     * 결제 처리 (모의 PG 승인).
     *
     * <p>모의 PG는 항상 승인한다. 거절 분기는 017.
     * 이미 paid인 주문 재요청은 200 + 기존 결제 결과(멱등).
     *
     * @param orderId  주문 ID
     * @param request  결제 요청 (선택 — null이면 기본값 사용)
     * @param auth     JWT 인증
     * @return 결제 응답 200
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable long orderId,
            @RequestBody(required = false) PaymentRequest request,
            Authentication auth
    ) {
        PaymentResponse response = paymentServiceResponse.pay(auth, orderId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 상태 조회.
     *
     * <p>상태 조회는 이벤트 완결성 검증을 수행하지 않으므로 409로 깨지지 않는다(#3).
     *
     * @param orderId 주문 ID
     * @param auth    JWT 인증
     * @return 결제 상태 응답 200
     */
    @GetMapping
    public ResponseEntity<PaymentResponse> getPaymentStatus(
            @PathVariable long orderId,
            Authentication auth
    ) {
        PaymentResponse response = paymentServiceResponse.getPaymentStatus(auth, orderId);
        return ResponseEntity.ok(response);
    }
}
