package com.shop.shop.payment.spi;

import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.dto.PaymentStatusView;

/**
 * 결제 View 전용 facade — published port (payment 소유).
 *
 * <p>web 모듈의 결제 ViewController가 payment 도메인 내부 Service·Entity를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 payment 내부 {@code service} 패키지에 위치한다.
 *
 * <p>email을 인자로 받아 내부에서 {@code member.spi.MemberDirectory}로 userId로 변환한다
 * (OrderFacade 선례).
 *
 * <p>web 타입({@code OrderPaymentForm})을 받지 않고 payment 소유 DTO만 받는다(#1).
 * web 핸들러가 {@code OrderPaymentForm → PaymentRequest}로 변환한 뒤 facade를 호출한다.
 *
 * <p>의존 방향: web → payment.spi 단방향. payment는 web을 참조하지 않는다.
 */
public interface PaymentFacade {

    /**
     * 결제 처리 — form-login email + payment 소유 DTO 기반.
     *
     * <p>처리 흐름: email→userId(member.spi) → PaymentRequest→PaymentCommand 변환
     * → {@link com.shop.shop.payment.service.PaymentService#pay} 위임.
     *
     * @param email   form-login principal email
     * @param orderId 주문 ID
     * @param request 결제 요청 (payment 소유 DTO — web 타입 아님)
     * @return 결제 결과
     */
    PaymentResponse pay(String email, long orderId, PaymentRequest request);

    /**
     * 결제 상태 조회 — form-login email 기반.
     *
     * <p>상태 조회 경로: getOrderSnapshot(소유권만 검증, 완결성 409 없음) 사용(#3).
     *
     * @param email   form-login principal email
     * @param orderId 주문 ID
     * @return 결제 상태 뷰 (주문 상세 결제 영역용)
     */
    PaymentStatusView getPaymentStatus(String email, long orderId);
}
