package com.shop.shop.member.dto;

import com.shop.shop.member.MemberPasswordPolicy;
import com.shop.shop.member.dto.validation.PasswordMatches;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 REST 요청 DTO (record).
 *
 * <p>클래스 레벨 {@link PasswordMatches}로 newPassword/newPasswordConfirm 교차검증.
 * field="newPassword", confirmField="newPasswordConfirm" — SignupRequest default와 구분.
 * currentPassword는 서비스 계층에서 BCrypt.matches로 검증한다.
 */
@PasswordMatches(field = "newPassword", confirmField = "newPasswordConfirm")
public record PasswordChangeRequest(

        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = MemberPasswordPolicy.MIN_LENGTH,
              message = "새 비밀번호는 최소 " + MemberPasswordPolicy.MIN_LENGTH + "자 이상이어야 합니다.")
        String newPassword,

        @NotBlank(message = "새 비밀번호 확인은 필수입니다.")
        String newPasswordConfirm
) {
}
