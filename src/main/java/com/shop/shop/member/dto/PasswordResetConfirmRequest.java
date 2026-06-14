package com.shop.shop.member.dto;

import com.shop.shop.member.MemberPasswordPolicy;
import com.shop.shop.member.dto.validation.PasswordMatches;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 확정 REST DTO.
 *
 * <p>{@link PasswordMatches} 필드 속성 명시 필수:
 * 기본값(field="password", confirmField="passwordConfirm")은 newPassword/newPasswordConfirm을 찾지 못해
 * validator가 null==null로 조용히 통과하는 보안 결함이 발생한다(PasswordMatchesValidator:42-44).
 * 029가 도입한 field/confirmField 일반화를 활용해 양쪽 속성을 명시한다.
 *
 * <p>최소 길이: {@link MemberPasswordPolicy#MIN_LENGTH} 재사용 (SignupRequest 동일 출처).
 */
@PasswordMatches(field = "newPassword", confirmField = "newPasswordConfirm")
public record PasswordResetConfirmRequest(
        @NotBlank(message = "토큰은 필수입니다.")
        String token,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = MemberPasswordPolicy.MIN_LENGTH,
                message = "비밀번호는 최소 " + MemberPasswordPolicy.MIN_LENGTH + "자 이상이어야 합니다.")
        String newPassword,

        @NotBlank(message = "비밀번호 확인은 필수입니다.")
        String newPasswordConfirm
) {
}
