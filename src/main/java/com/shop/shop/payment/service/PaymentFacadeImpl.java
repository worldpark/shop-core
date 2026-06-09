package com.shop.shop.payment.service;

import com.shop.shop.member.spi.MemberDirectory;
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
 */
@Service
@RequiredArgsConstructor
class PaymentFacadeImpl implements PaymentFacade {

    private final PaymentService paymentService;
    private final MemberDirectory memberDirectory;
    private final PaymentDtoMapper dtoMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public PaymentResponse pay(String email, long orderId, PaymentRequest request) {
        long userId = memberDirectory.findUserIdByEmail(email);
        PaymentService.PaymentCommand cmd = new PaymentService.PaymentCommand(
                request.methodOrDefault(),
                request.amount()
        );
        PaymentService.PaymentResult result = paymentService.pay(userId, orderId, cmd);
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
}
