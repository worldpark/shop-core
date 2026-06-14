package com.shop.shop.web.auth;

import com.shop.shop.member.MemberPasswordPolicy;
import com.shop.shop.member.dto.validation.PasswordMatches;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 비밀번호 재설정 확정 View 폼 백킹 객체.
 *
 * <p>모델 키: {@code passwordResetConfirmForm}.
 * GET /password-reset/confirm → token 프리필 hidden. POST /password-reset/confirm → @Valid 검증.
 *
 * <p>{@link PasswordMatches} 필드 속성 명시 필수:
 * 기본값(field="password", confirmField="passwordConfirm")은 newPassword/newPasswordConfirm을 찾지 못해
 * validator가 null==null로 조용히 통과하는 보안 결함이 발생한다(PasswordMatchesValidator:42-44).
 * 029가 도입한 field/confirmField 일반화를 활용해 양쪽 속성을 명시한다.
 *
 * <p>최소 길이: {@link MemberPasswordPolicy#MIN_LENGTH} 재사용 (SignupForm 동일 출처, 리터럴 하드코딩 금지).
 *
 * <p>비밀번호 echo 차단: 검증 실패 재렌더 시 PasswordResetViewController가
 * newPassword/newPasswordConfirm을 null로 clear한다.
 * token 필드는 hidden으로 유지 (echo 허용).
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩에 최적화.
 */
@PasswordMatches(field = "newPassword", confirmField = "newPasswordConfirm")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetConfirmForm {

    /** 재설정 토큰 원문. hidden 필드로 폼에 유지됨. */
    private String token;

    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Size(min = MemberPasswordPolicy.MIN_LENGTH,
            message = "비밀번호는 최소 " + MemberPasswordPolicy.MIN_LENGTH + "자 이상이어야 합니다.")
    private String newPassword;

    @NotBlank(message = "비밀번호 확인은 필수입니다.")
    private String newPasswordConfirm;
}
