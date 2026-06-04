package com.shop.shop.member.dto;

import com.shop.shop.member.MemberPasswordPolicy;
import com.shop.shop.member.dto.validation.PasswordMatches;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * REST 회원가입 요청 DTO.
 *
 * <p>클래스 레벨 {@link PasswordMatches}로 password/passwordConfirm 교차검증.
 * 기본 role은 CONSUMER로 강제 — 요청에서 role을 받지 않는다 (Constraint).
 * phone은 optional.
 */
@PasswordMatches
public record SignupRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = MemberPasswordPolicy.MIN_LENGTH, message = "비밀번호는 최소 " + MemberPasswordPolicy.MIN_LENGTH + "자 이상이어야 합니다.")
        String password,

        @NotBlank(message = "비밀번호 확인은 필수입니다.")
        String passwordConfirm,

        @NotBlank(message = "이름은 필수입니다.")
        String name,

        String phone   // optional
) {
}
