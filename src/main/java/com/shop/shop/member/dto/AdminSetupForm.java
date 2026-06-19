package com.shop.shop.member.dto;

import com.shop.shop.member.MemberPasswordPolicy;
import com.shop.shop.member.dto.validation.PasswordMatches;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 최초 ADMIN 부트스트랩 폼 백킹 객체.
 *
 * <p>{@link SignupForm}을 미러링하되 phone 필드를 제외한다 (ADMIN 부트스트랩은 phone 불필요).
 *
 * <p>가변 POJO — Spring MVC {@code @ModelAttribute} 데이터 바인딩 + Thymeleaf th:field 재렌더에 최적화.
 * record는 불변이라 검증 실패 재렌더 시 필드 echo 경로가 취약하므로 가변 클래스 채택.
 *
 * <p>클래스 레벨 {@link PasswordMatches}로 password/passwordConfirm 교차검증.
 * 검증 실패 재렌더 시 {@code AdminSetupViewController}가 password/passwordConfirm을 null로 clear.
 */
@PasswordMatches
@Getter
@Setter
@NoArgsConstructor
public class AdminSetupForm {

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "비밀번호는 필수입니다.")
    @Size(min = MemberPasswordPolicy.MIN_LENGTH, message = "비밀번호는 최소 " + MemberPasswordPolicy.MIN_LENGTH + "자 이상이어야 합니다.")
    private String password;

    @NotBlank(message = "비밀번호 확인은 필수입니다.")
    private String passwordConfirm;

    @NotBlank(message = "이름은 필수입니다.")
    private String name;
}
