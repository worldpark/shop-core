package com.shop.shop.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * View 상품 등록/수정 폼 백킹 객체.
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩 + Thymeleaf th:field 재렌더에 최적화.
 * record는 불변이라 검증 실패 재렌더 시 필드 echo 경로가 취약하므로 가변 클래스 채택(SignupForm 패턴 계승).
 *
 * <p>폼 필드명 계약(view-implementor와 정합):
 * categoryId / name / description / basePrice / status
 *
 * <p>status는 {@code String} 타입 — web 모듈이 도메인 enum({@code ProductStatus})을 직접 참조하지
 * 않도록 View 전용 facade 경계에서 String으로 받는다. facade 구현이 String → ProductStatus 변환 담당.
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductForm {

    private Long categoryId;

    @NotBlank(message = "상품명은 필수입니다.")
    private String name;

    private String description;

    @NotNull(message = "기본 가격은 필수입니다.")
    @DecimalMin(value = "0.0", message = "기본 가격은 0 이상이어야 합니다.")
    private BigDecimal basePrice;

    /** 상품 상태 문자열 (DRAFT/ON_SALE/SOLD_OUT/HIDDEN). 수정 시에만 사용, 등록 시 null. */
    private String status;
}
