package com.shop.shop.web.coupon;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 쿠폰 코드 발급(claim) 폼 백킹 객체 (View 전용).
 *
 * <p>Entity 직접 바인딩 금지. {@code @NotBlank}로 서버 사이드 1차 검증.
 * 도메인 검증(활성/유효기간/중복 등)은 {@link com.shop.shop.order.spi.CouponFacade#claim} 경유.
 */
@Getter
@Setter
public class CouponClaimForm {

    @NotBlank(message = "쿠폰 코드를 입력해 주세요.")
    private String code;
}
