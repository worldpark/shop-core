package com.shop.shop.member.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회원 정보 수정 View 폼 백킹 객체.
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩 + Thymeleaf th:field 재렌더에 최적화.
 * phone은 optional (007 규칙 계승 — null/빈 문자열 허용).
 */
@Getter
@Setter
@NoArgsConstructor
public class ProfileUpdateForm {

    @NotBlank(message = "이름은 필수입니다.")
    private String name;

    private String phone;   // optional
}
