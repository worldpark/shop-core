package com.shop.shop.payment.service;

import com.shop.shop.common.exception.PaymentDeclinedException;
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
 *
 * <p>거절 처리(C1·Ma3): {@code PaymentService.pay}가 커밋한 거절 결과를 받아
 * 트랜잭션 밖에서 {@link PaymentDeclinedException}(402)으로 변환해 던진다.
 * 이 throw는 트랜잭션 밖이므로 이미 커밋된 {@code failed}/이벤트를 롤백시키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceResponse {

    private final PaymentService paymentService;
    private final PaymentDtoMapper dtoMapper;

    /**
     * 결제 처리 (REST).
     *
     * <p>거절 시: {@link PaymentDeclinedException}(402, failureReason)을 throw한다(C1·Ma3).
     * {@code RestExceptionHandler}의 {@code BusinessException} 핸들러가 402 + ErrorResponse로 자동 매핑.
     *
     * @param auth    JWT 인증 (principal = userId(long))
     * @param orderId 주문 ID
     * @param request 결제 요청 DTO (null 허용 — 미전달 시 기본값 사용)
     * @return 결제 응답 DTO (승인/멱등 시만 반환)
     * @throws PaymentDeclinedException 거절 시 402 (트랜잭션 밖에서 throw, C1)
     */
    public PaymentResponse pay(Authentication auth, long orderId, PaymentRequest request) {
        long userId = (long) auth.getPrincipal();
        PaymentRequest safeRequest = request != null ? request : new PaymentRequest(null, null);
        PaymentService.PaymentCommand cmd = new PaymentService.PaymentCommand(
                safeRequest.methodOrDefault(),
                safeRequest.amount()
        );
        PaymentService.PaymentResult result = paymentService.pay(userId, orderId, cmd);

        // 거절 결과를 커밋 이후 트랜잭션 밖에서 402로 변환 (C1 — 롤백 방지)
        if (result.declined()) {
            throw new PaymentDeclinedException(result.failureReason());
        }

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
