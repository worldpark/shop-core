package com.shop.shop.web.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Variant 생성/수정 폼 백킹 객체.
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩 + Thymeleaf th:field 재렌더에 최적화.
 * ProductForm 스타일 계승.
 *
 * <p>도메인 타입 import 금지(필드 타입: String / BigDecimal / Integer / boolean / List&lt;Long&gt;만 사용).
 */
@Getter
@Setter
@NoArgsConstructor
public class VariantForm {

    @NotBlank(message = "SKU는 필수입니다.")
    private String sku;

    @NotNull(message = "가격은 필수입니다.")
    @DecimalMin(value = "0.0", message = "가격은 0 이상이어야 합니다.")
    private BigDecimal price;

    @NotNull(message = "재고는 필수입니다.")
    @Min(value = 0, message = "재고는 0 이상이어야 합니다.")
    private Integer stock;

    private boolean active;

    private List<Long> optionValueIds;
}
