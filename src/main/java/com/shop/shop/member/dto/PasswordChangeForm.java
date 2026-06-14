package com.shop.shop.member.dto;

import com.shop.shop.member.MemberPasswordPolicy;
import com.shop.shop.member.dto.validation.PasswordMatches;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 비밀번호 변경 View 폼 백킹 객체.
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩 + Thymeleaf th:field 재렌더에 최적화.
 * 클래스 레벨 {@link PasswordMatches}로 newPassword/newPasswordConfirm 교차검증.
 * 검증 실패 재렌더 시 AccountViewController가 newPassword/newPasswordConfirm을 null로 clear (비번 echo 금지).
 */
@PasswordMatches(field = "newPassword", confirmField = "newPasswordConfirm")
@Getter
@Setter
@NoArgsConstructor
public class PasswordChangeForm {

    @NotBlank(message = "현재 비밀번호는 필수입니다.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Size(min = MemberPasswordPolicy.MIN_LENGTH,
          message = "새 비밀번호는 최소 " + MemberPasswordPolicy.MIN_LENGTH + "자 이상이어야 합니다.")
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인은 필수입니다.")
    private String newPasswordConfirm;
}
