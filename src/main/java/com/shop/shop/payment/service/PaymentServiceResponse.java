package com.shop.shop.payment.service;

import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 결제 REST 전용 조합 서비스 (package-private 아닌 public — RestController가 직접 참조).
 *
 * <p>REST Controller가 ServiceResponse를 통해 서비스를 호출한다(architecture-rule REST 레이어).
 * 비즈니스 로직 없음 — Authentication에서 userId 추출 + DTO 변환 + PaymentService 위임.
 *
 * <p>principal 이중경로 통일: REST 체인은 JWT 필터가 userId(long)를 principal로 주입한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceResponse {

    private final PaymentService paymentService;
    private final PaymentDtoMapper dtoMapper;

    /**
     * 결제 처리 (REST).
     *
     * @param auth    JWT 인증 (principal = userId(long))
     * @param orderId 주문 ID
     * @param request 결제 요청 DTO (null 허용 — 미전달 시 기본값 사용)
     * @return 결제 응답 DTO
     */
    public PaymentResponse pay(Authentication auth, long orderId, PaymentRequest request) {
        long userId = (long) auth.getPrincipal();
        PaymentRequest safeRequest = request != null ? request : new PaymentRequest(null, null);
        PaymentService.PaymentCommand cmd = new PaymentService.PaymentCommand(
                safeRequest.methodOrDefault(),
                safeRequest.amount()
        );
        PaymentService.PaymentResult result = paymentService.pay(userId, orderId, cmd);
        return dtoMapper.toPaymentResponse(result);
    }

    /**
     * 결제 상태 조회 (REST).
     *
     * @param auth    JWT 인증 (principal = userId(long))
     * @param orderId 주문 ID
     * @return 결제 응답 DTO
     */
    public PaymentResponse getPaymentStatus(Authentication auth, long orderId) {
        long userId = (long) auth.getPrincipal();
        PaymentService.PaymentStatusResult result = paymentService.getPaymentStatus(userId, orderId);
        return dtoMapper.toPaymentResponseFromStatus(result);
    }
}
