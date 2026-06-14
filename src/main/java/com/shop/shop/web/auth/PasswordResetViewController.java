package com.shop.shop.web.auth;

import com.shop.shop.common.exception.InvalidPasswordResetTokenException;
import com.shop.shop.member.spi.PasswordResetFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 비밀번호 재설정 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /password-reset}, {@code /password-reset/**} → permitAll.
 * 비로그인 사용자 전용 흐름.
 *
 * <p>레이어: PasswordResetViewController(@Controller) → {@link PasswordResetFacade}(published port)
 * → PasswordResetService → Redis 토큰 스토어.
 * web은 member Entity/Service/Role을 직접 참조하지 않는다 (facade만).
 *
 * <p>모델 키 계약:
 * <ul>
 *   <li>{@code passwordResetForm} — {@link PasswordResetForm} (이메일 폼)</li>
 *   <li>{@code passwordResetConfirmForm} — {@link PasswordResetConfirmForm} (새 비번 폼)</li>
 *   <li>{@code resetTokenValid} — scalar boolean (토큰 유효성, Thymeleaf 예약어 충돌 회피)</li>
 * </ul>
 *
 * <p>enumeration 방지: POST /password-reset 는 이메일 존재 여부와 무관하게 항상 redirect:/password-reset?sent.
 *
 * <p>비밀번호 echo 차단: 검증 실패/예외 재렌더 시 newPassword/newPasswordConfirm을 null로 clear.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PasswordResetViewController {

    private final PasswordResetFacade passwordResetFacade;

    // ============================================================
    // GET /password-reset — 비밀번호 재설정 요청 폼
    // ============================================================

    /**
     * 비밀번호 재설정 요청 화면.
     * GET /password-reset
     *
     * <p>빈 passwordResetForm을 모델에 추가.
     * param.sent 분기 안내는 템플릿에서 처리.
     *
     * @param model Spring MVC 모델
     * @return view name "auth/password-reset-request"
     */
    @GetMapping("/password-reset")
    public String resetRequestForm(Model model) {
        model.addAttribute("passwordResetForm", new PasswordResetForm());
        return "auth/password-reset-request";
    }

    // ============================================================
    // POST /password-reset — 비밀번호 재설정 요청 제출
    // ============================================================

    /**
     * 비밀번호 재설정 요청 처리.
     * POST /password-reset
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>@Valid 검증 실패 → 재렌더 auth/password-reset-request</li>
     *   <li>성공 → {@link PasswordResetFacade#requestReset} 호출 (존재/미존재 무관 정상 반환)</li>
     *   <li>항상 → redirect:/password-reset?sent (enumeration 방지)</li>
     * </ol>
     *
     * @param form          이메일 폼 (모델 키 "passwordResetForm")
     * @param bindingResult 검증 결과
     * @return view name 또는 redirect
     */
    @PostMapping("/password-reset")
    public String resetRequest(
            @Valid @ModelAttribute("passwordResetForm") PasswordResetForm form,
            BindingResult bindingResult) {

        // 1. Bean Validation 실패 처리 (@Email/@NotBlank)
        if (bindingResult.hasErrors()) {
            return "auth/password-reset-request";
        }

        // 2. 재설정 요청 (이메일 존재/미존재 무관 항상 정상 반환 — enumeration 방지)
        passwordResetFacade.requestReset(form.getEmail());

        // 3. 항상 동일 안내 redirect (존재/미존재 동일 — enumeration 방지)
        return "redirect:/password-reset?sent";
    }

    // ============================================================
    // GET /password-reset/confirm — 새 비밀번호 입력 폼
    // ============================================================

    /**
     * 새 비밀번호 입력 화면.
     * GET /password-reset/confirm?token=...
     *
     * <p>토큰 유효성을 비소비 peek으로 확인.
     * 유효하면 폼 표시, 무효면 안내 화면 표시 (예외 없이 정상 렌더).
     *
     * <p>GET은 토큰을 소비하지 않는다 (새로고침으로 토큰이 소진되는 사고 방지).
     * 1회용 소비는 POST 성공 시에만 수행.
     *
     * @param token 쿼리 파라미터 토큰 (없으면 null)
     * @param model Spring MVC 모델
     * @return view name "auth/password-reset-confirm"
     */
    @GetMapping("/password-reset/confirm")
    public String confirmForm(
            @RequestParam(required = false) String token,
            Model model) {

        // 비소비 peek — 토큰 유효성만 확인하고 삭제하지 않음
        boolean resetTokenValid = token != null && passwordResetFacade.isTokenValid(token);

        model.addAttribute("resetTokenValid", resetTokenValid);

        // token 프리필 hidden
        PasswordResetConfirmForm confirmForm = new PasswordResetConfirmForm();
        confirmForm.setToken(token);
        model.addAttribute("passwordResetConfirmForm", confirmForm);

        return "auth/password-reset-confirm";
    }

    // ============================================================
    // POST /password-reset/confirm — 새 비밀번호 확정 제출
    // ============================================================

    /**
     * 새 비밀번호 확정 처리.
     * POST /password-reset/confirm
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>@Valid 검증 실패(@Size/@PasswordMatches) → 비번 clear + resetTokenValid=true 재렌더</li>
     *   <li>{@link PasswordResetFacade#confirmReset} 호출 (토큰 소비 + 비번 교체)</li>
     *   <li>{@link InvalidPasswordResetTokenException} → resetTokenValid=false + 안내 + 비번 clear 재렌더</li>
     *   <li>성공 → redirect:/login?reset</li>
     * </ol>
     *
     * <p>비밀번호 echo 차단: 검증 실패/예외 재렌더 시 newPassword/newPasswordConfirm을 null로 clear.
     *
     * @param form          새 비번 폼 (모델 키 "passwordResetConfirmForm")
     * @param bindingResult 검증 결과
     * @param model         Spring MVC 모델
     * @return view name 또는 redirect
     */
    @PostMapping("/password-reset/confirm")
    public String confirmReset(
            @Valid @ModelAttribute("passwordResetConfirmForm") PasswordResetConfirmForm form,
            BindingResult bindingResult,
            Model model) {

        // 1. Bean Validation 실패 처리 (@NotBlank/@Size/@PasswordMatches)
        if (bindingResult.hasErrors()) {
            clearPasswordFields(form);
            model.addAttribute("resetTokenValid", true);
            return "auth/password-reset-confirm";
        }

        // 2. 비밀번호 재설정 확정 (토큰 소비 + 비번 교체)
        try {
            passwordResetFacade.confirmReset(form.getToken(), form.getNewPassword());
        } catch (InvalidPasswordResetTokenException e) {
            // 3. 무효/만료/사용 토큰 → resetTokenValid=false + 안내 재렌더
            log.warn("비밀번호 재설정 토큰 무효: reason={}", e.getMessage());
            clearPasswordFields(form);
            model.addAttribute("resetTokenValid", false);
            return "auth/password-reset-confirm";
        }

        // 4. 성공 → /login?reset (PRG 패턴)
        return "redirect:/login?reset";
    }

    // ============================================================
    // private helpers
    // ============================================================

    /**
     * 비밀번호 필드 echo 차단.
     * 검증 실패/예외 재렌더 시 newPassword/newPasswordConfirm을 null로 clear.
     * token은 유지 (hidden 필드).
     */
    private void clearPasswordFields(PasswordResetConfirmForm form) {
        form.setNewPassword(null);
        form.setNewPasswordConfirm(null);
    }
}
