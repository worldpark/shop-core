package com.shop.shop.web.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 비밀번호 재설정 요청 View 폼 백킹 객체.
 *
 * <p>모델 키: {@code passwordResetForm}.
 * GET /password-reset → 빈 폼. POST /password-reset → @Valid 검증.
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩에 최적화.
 */
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetForm {

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;
}
