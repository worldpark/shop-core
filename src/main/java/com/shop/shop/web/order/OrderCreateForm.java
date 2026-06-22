package com.shop.shop.web.order;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 주문 생성 폼 백킹 객체 (View 전용).
 *
 * <p>배송지 필수 4종 + 선택 address2 + 선택 userCouponId(쿠폰 적용, 057 추가).
 * 컨트롤러에서 {@code @Valid @ModelAttribute OrderCreateForm} 으로 바인딩하고,
 * 검증 통과 후 {@link com.shop.shop.order.dto.OrderCreateRequest} 로 변환해 facade에 전달한다.
 *
 * <p>{@code userCouponId}: 체크아웃 화면에서 선택한 쿠폰 ID(라디오/셀렉트). 미선택 시 null.
 * HTML 라디오의 쿠폰 미적용 옵션 value=""(빈 문자열)은 Spring이 Long에 바인딩할 때 null로 변환한다.
 */
@Getter
@Setter
public class OrderCreateForm {

    @NotBlank(message = "수령인 이름을 입력해 주세요.")
    private String recipient;

    @NotBlank(message = "전화번호를 입력해 주세요.")
    private String phone;

    @NotBlank(message = "우편번호를 입력해 주세요.")
    private String postcode;

    @NotBlank(message = "주소를 입력해 주세요.")
    private String address1;

    private String address2;

    /**
     * 적용할 쿠폰 ID (선택). 미선택 또는 쿠폰 미적용 시 null.
     * OrderCreateRequest.userCouponId(Long, 031 필드)로 전달된다.
     */
    private Long userCouponId;
}
