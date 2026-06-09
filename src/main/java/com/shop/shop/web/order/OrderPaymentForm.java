package com.shop.shop.web.order;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 결제 폼 백킹 객체 (web 소유 — View 전용).
 *
 * <p>facade({@link com.shop.shop.payment.spi.PaymentFacade})로 직접 전달하지 않는다(#1).
 * 컨트롤러 핸들러가 {@code OrderPaymentForm → payment.dto.PaymentRequest}로 변환한 뒤
 * facade를 호출한다(web→domain.spi 단방향, architecture-rule, revision #1).
 *
 * <p>필드:
 * <ul>
 *   <li>{@code method} — 결제 수단. null/빈 문자열이면 서비스에서 기본값 "mock" 적용.</li>
 *   <li>{@code amount} — 결제 금액(선택). 전달 시 서버 finalAmount와 일치 검증(불일치 400).
 *       016 모의 결제에서는 서버 권위라 미전달(null)로 두면 서버가 주문 금액을 사용한다.</li>
 * </ul>
 */
@Getter
@Setter
public class OrderPaymentForm {

    /** 결제 수단 (선택 — null/빈 문자열 시 기본 "mock"). */
    private String method = "mock";

    /** 결제 금액 (선택 — null 시 서버 주문 finalAmount 사용). */
    private BigDecimal amount;
}
