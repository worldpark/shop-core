package com.shop.shop.web.member;

import com.shop.shop.common.exception.AdminAlreadyExistsException;
import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.member.dto.AdminSetupForm;
import com.shop.shop.member.spi.AdminBootstrapFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 최초 ADMIN 부트스트랩 화면 진입점.
 *
 * <p>레이어: ViewController → {@link AdminBootstrapFacade}(published port) → MemberService → MemberRepository.
 * 모델엔 DTO/ViewModel만 담는다 (Entity 직접 전달 금지).
 * facade만 의존한다 (member.service·Repository 직접 참조 금지).
 *
 * <p>보안 불변식 (핵심):
 * ADMIN이 이미 존재하면 GET·POST 양쪽에서 redirect:/login — 공개 엔드포인트의 권한 상승 차단.
 * POST는 GET 게이트만으로 못 막으므로 {@link AdminBootstrapFacade#createFirstAdmin}이
 * 트랜잭션 내 {@code countByRole(ADMIN) != 0}을 재확인하는 것이 최종 방어선.
 *
 * <p>비밀번호 echo 차단:
 * 검증 실패·이메일 중복 재렌더 시 password/passwordConfirm을 clear — 보안상 echo 금지.
 *
 * <p>{@link MemberSignupViewController} 패턴을 그대로 따른다.
 */
@Controller
@RequiredArgsConstructor
public class AdminSetupViewController {

    private final AdminBootstrapFacade adminBootstrapFacade;

    /**
     * 최초 ADMIN 부트스트랩 화면 표시.
     * GET /setup/admin
     *
     * <p>ADMIN이 이미 존재하면 redirect:/login (부트스트랩 폐쇄).
     * 아니면 빈 {@link AdminSetupForm} 모델 키 "adminSetupForm"으로 model에 추가 + 뷰 "auth/admin-setup".
     */
    @GetMapping("/setup/admin")
    public String setupAdminForm(Model model) {
        if (adminBootstrapFacade.adminExists()) {
            return "redirect:/login";
        }
        model.addAttribute("adminSetupForm", new AdminSetupForm());
        return "auth/admin-setup";
    }

    /**
     * 최초 ADMIN 부트스트랩 처리.
     * POST /setup/admin
     *
     * <p>CSRF 보호 대상 — Thymeleaf th:action="@{/setup/admin}"으로 _csrf 자동 주입.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>{@code @Valid} 검증 실패 → 비번 clear → "auth/admin-setup" 재렌더 (필드 에러 표시)</li>
     *   <li>{@link AdminBootstrapFacade#createFirstAdmin} 호출</li>
     *   <li>{@link DuplicateEmailException} → email 필드 에러 바인딩 → 비번 clear → "auth/admin-setup" 재렌더</li>
     *   <li>{@link AdminAlreadyExistsException} → redirect:/login (이미 닫힌 화면, 생성 안 됨)</li>
     *   <li>성공 → redirect:/login?adminCreated</li>
     * </ol>
     *
     * @param form          폼 백킹 객체 (모델 키 "adminSetupForm")
     * @param bindingResult 검증 결과 (반드시 form 파라미터 바로 다음 위치)
     * @return view name 또는 redirect
     */
    @PostMapping("/setup/admin")
    public String setupAdmin(
            @Valid @ModelAttribute("adminSetupForm") AdminSetupForm form,
            BindingResult bindingResult) {

        // 1. Bean Validation 실패 처리 (@Valid — @Email/@NotBlank/@Size/@PasswordMatches)
        if (bindingResult.hasErrors()) {
            clearPasswords(form);   // 비밀번호 원문 echo 차단 (Constraint)
            return "auth/admin-setup";
        }

        // 2. ADMIN 부트스트랩 도메인 로직 실행 (facade 경유 — 비즈니스 로직은 Service에)
        try {
            adminBootstrapFacade.createFirstAdmin(form.getEmail(), form.getPassword(), form.getName());
        } catch (DuplicateEmailException e) {
            // 3. 이메일 중복 — email 필드 에러로 변환 후 재렌더 (JSON 반환 금지)
            bindingResult.rejectValue("email", "duplicate", "이미 사용 중인 이메일입니다.");
            clearPasswords(form);   // 비밀번호 원문 echo 차단
            return "auth/admin-setup";
        } catch (AdminAlreadyExistsException e) {
            // 4. 이미 ADMIN 존재 (직접 POST/동시 경합) — redirect:/login (화면 폐쇄, 정보 최소 노출)
            return "redirect:/login";
        }

        // 5. 성공 → /login?adminCreated 으로 redirect (PRG 패턴)
        return "redirect:/login?adminCreated";
    }

    /**
     * 비밀번호 필드 echo 차단.
     * 검증 실패 재렌더 시 password/passwordConfirm 값을 null로 clear.
     * 이메일/name은 유지 (사용자 편의).
     */
    private void clearPasswords(AdminSetupForm form) {
        form.setPassword(null);
        form.setPasswordConfirm(null);
    }
}
