package com.shop.shop.web.product;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 옵션값 생성 폼 백킹 객체.
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩 + Thymeleaf th:field 재렌더에 최적화.
 * ProductForm 스타일 계승.
 *
 * <p>도메인 타입 import 금지(필드 타입: String만 사용).
 */
@Getter
@Setter
@NoArgsConstructor
public class OptionValueForm {

    @NotBlank(message = "옵션값은 필수입니다.")
    private String value;
}
