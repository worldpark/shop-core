package com.shop.shop.payment.service;

import com.shop.shop.common.exception.PaymentDeclinedException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.payment.dto.OrderCancelResponse;
import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.dto.PaymentStatusView;
import com.shop.shop.payment.spi.PaymentFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link PaymentFacade} 구현체 (package-private).
 *
 * <p>payment 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link PaymentFacade})만 참조하며, 이 구현체를 직접 알지 못한다(OrderFacadeImpl 선례).
 *
 * <p>책임:
 * <ul>
 *   <li>form-login email → userId 변환: {@link MemberDirectory#findUserIdByEmail(String)}</li>
 *   <li>PaymentRequest → PaymentCommand 변환</li>
 *   <li>PaymentService 위임</li>
 *   <li>PaymentDtoMapper를 통한 내부 타입 → DTO 변환</li>
 * </ul>
 *
 * <p>web 타입({@code OrderPaymentForm})을 받지 않는다(#1).
 * 변환은 web 핸들러 책임.
 *
 * <p>거절 처리(C1·Ma3): {@code PaymentService.pay}가 커밋한 거절 결과를 받아
 * 트랜잭션 밖(facade는 트랜잭션 밖)에서 {@link PaymentDeclinedException}(402, failureReason)을 throw한다.
 * View 핸들러가 {@code catch (BusinessException e) → flashError(e.getMessage()) + redirect}로 처리한다.
 * (이미 커밋된 failed/이벤트를 롤백시키지 않는다)
 */
@Service
@RequiredArgsConstructor
class PaymentFacadeImpl implements PaymentFacade {

    private final PaymentService paymentService;
    private final MemberDirectory memberDirectory;
    private final PaymentDtoMapper dtoMapper;

    /**
     * {@inheritDoc}
     *
     * <p>거절 시: {@link PaymentDeclinedException}(402, failureReason)을 throw한다(C1·Ma3).
     * View 핸들러의 {@code catch (BusinessException e)} 절이 flashError로 처리한다.
     *
     * @throws PaymentDeclinedException 거절 시 402 (트랜잭션 밖에서 throw, C1)
     */
    @Override
    public PaymentResponse pay(String email, long orderId, PaymentRequest request) {
        long userId = memberDirectory.findUserIdByEmail(email);
        PaymentService.PaymentCommand cmd = new PaymentService.PaymentCommand(
                request.methodOrDefault(),
                request.amount()
        );
        PaymentService.PaymentResult result = paymentService.pay(userId, orderId, cmd);

        // 거절 결과를 커밋 이후 트랜잭션 밖에서 402로 변환 (C1 — 롤백 방지)
        if (result.declined()) {
            throw new PaymentDeclinedException(result.failureReason());
        }

        return dtoMapper.toPaymentResponse(result);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PaymentStatusView getPaymentStatus(String email, long orderId) {
        long userId = memberDirectory.findUserIdByEmail(email);
        PaymentService.PaymentStatusResult result = paymentService.getPaymentStatus(userId, orderId);
        return dtoMapper.toPaymentStatusView(result);
    }

    /**
     * {@inheritDoc}
     *
     * <p>거부 예외(409/404)는 PaymentService가 트랜잭션 안에서 던지므로 그대로 전파(C1 비적용 — #2).
     * View 핸들러의 {@code catch (BusinessException e)} 절이 flashError로 처리한다.
     */
    @Override
    public OrderCancelResponse cancel(String email, long orderId) {
        long userId = memberDirectory.findUserIdByEmail(email);
        PaymentService.CancelResult result = paymentService.cancel(userId, orderId);
        return new OrderCancelResponse(
                result.orderId(),
                result.orderNumber(),
                result.orderStatus(),
                result.isRefunded(),
                result.refundedAmount(),
                result.currency()
        );
    }
}
