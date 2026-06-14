package com.shop.shop.web.member;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.dto.AccountInfo;
import com.shop.shop.member.dto.PasswordChangeForm;
import com.shop.shop.member.dto.ProfileUpdateForm;
import com.shop.shop.member.spi.AccountFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 계정 self-service View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /account}, {@code /account/**} → {@code authenticated}.
 * 비인증 → /login redirect (View 체인 기본 동작).
 *
 * <p>레이어: AccountViewController(@Controller) → {@link AccountFacade}(published port)
 * → AccountService → MemberRepository.
 * web은 member Entity/Service/Role enum 을 직접 참조하지 않는다.
 *
 * <p>principal 식별: formLogin 세션 기반, {@code authentication.getName()} == email.
 * AccountFacade 내부에서 email → userId 해석.
 *
 * <p>모델 키 계약:
 * <ul>
 *   <li>{@code accountInfo} — {@link AccountInfo} (email/name/phone, 비밀번호·해시 비노출)</li>
 *   <li>{@code passwordForm} — {@link PasswordChangeForm} (@ModelAttribute)</li>
 *   <li>{@code profileForm} — {@link ProfileUpdateForm} (@ModelAttribute)</li>
 * </ul>
 * 주의: 'account', 'request', 'param', 'session', 'application' 등 Thymeleaf 예약어 키 금지.
 * Flash 키: {@code flashSuccess} / {@code flashError} (기존 messages fragment 컨벤션).
 *
 * <p>비밀번호 echo 차단: 검증 실패 / BusinessException 재렌더 시
 * currentPassword/newPassword/newPasswordConfirm 를 null로 clear.
 *
 * <p>탈퇴 특이 사항: 성공 후 {@link SecurityContextLogoutHandler}로 HTTP 세션 무효화.
 * 세션 무효화 이후 flash attribute 가 소실되므로 flash 미사용.
 * 완료 안내는 {@code /login?withdraw} 쿼리 파라미터로 표시
 * (plan §3-3 보강1 — flash 사용 금지, param.withdraw 로 /login 에서 분기).
 */
@Slf4j
@Controller
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountViewController {

    private final AccountFacade accountFacade;

    // ============================================================
    // GET /account — 계정 설정 화면
    // ============================================================

    /**
     * 계정 설정 화면.
     * GET /account
     *
     * <p>표시용 DTO({@link AccountInfo}), 빈 비번 변경 폼, 프리필된 정보 수정 폼을 모델에 추가.
     *
     * @param auth  인증 객체 (username = email, formLogin 세션)
     * @param model Spring MVC 모델
     * @return view name "member/account"
     */
    @GetMapping
    public String account(Authentication auth, Model model) {
        String email = auth.getName();
        AccountInfo info = accountFacade.getAccountInfo(email);

        model.addAttribute("accountInfo", info);
        model.addAttribute("passwordForm", new PasswordChangeForm());

        ProfileUpdateForm profileForm = new ProfileUpdateForm();
        profileForm.setName(info.name());
        profileForm.setPhone(info.phone());
        model.addAttribute("profileForm", profileForm);

        return "member/account";
    }

    // ============================================================
    // POST /account/password — 비밀번호 변경 폼 제출
    // ============================================================

    /**
     * 비밀번호 변경 폼 제출.
     * POST /account/password
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>@Valid 검증 실패 → 비번 필드 clear → 재렌더 (accountInfo/profileForm 다시 채움)</li>
     *   <li>{@link AccountFacade#changePassword} 호출</li>
     *   <li>{@link BusinessException}(현재 비번 불일치) → currentPassword 필드 에러 → 비번 clear → 재렌더</li>
     *   <li>성공 → "redirect:/account?password" + flashSuccess (PRG 패턴)</li>
     * </ol>
     *
     * @param form          비번 변경 폼 (모델 키 "passwordForm")
     * @param bindingResult 검증 결과
     * @param auth          인증 객체
     * @param model         Spring MVC 모델
     * @param ra            RedirectAttributes (flash 속성)
     * @return view name 또는 redirect
     */
    @PostMapping("/password")
    public String changePassword(
            @Valid @ModelAttribute("passwordForm") PasswordChangeForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model,
            RedirectAttributes ra) {

        String email = auth.getName();

        // 1. Bean Validation 실패 처리 (@NotBlank/@Size/@PasswordMatches)
        if (bindingResult.hasErrors()) {
            clearPasswordFields(form);
            repopulateModel(email, model);
            return "member/account";
        }

        // 2. 비밀번호 변경 도메인 로직 실행 (facade 경유)
        try {
            accountFacade.changePassword(email, form.getCurrentPassword(), form.getNewPassword());
        } catch (BusinessException e) {
            // 3. 현재 비번 불일치 — currentPassword 필드 에러로 변환 후 재렌더
            log.warn("비밀번호 변경 실패: email={}, reason={}", email, e.getMessage());
            bindingResult.rejectValue("currentPassword", "invalid", e.getMessage());
            clearPasswordFields(form);
            repopulateModel(email, model);
            return "member/account";
        }

        // 4. 성공 → /account?password 로 redirect (PRG 패턴)
        ra.addFlashAttribute("flashSuccess", "비밀번호가 변경되었습니다.");
        return "redirect:/account?password";
    }

    // ============================================================
    // POST /account/profile — 정보 수정 폼 제출
    // ============================================================

    /**
     * 회원 정보 수정 폼 제출.
     * POST /account/profile
     *
     * <p>name/phone만 수정. email/role/password 불변(facade 보장).
     *
     * @param form          정보 수정 폼 (모델 키 "profileForm")
     * @param bindingResult 검증 결과
     * @param auth          인증 객체
     * @param model         Spring MVC 모델
     * @param ra            RedirectAttributes (flash 속성)
     * @return view name 또는 redirect
     */
    @PostMapping("/profile")
    public String updateProfile(
            @Valid @ModelAttribute("profileForm") ProfileUpdateForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model,
            RedirectAttributes ra) {

        String email = auth.getName();

        // 1. Bean Validation 실패 처리 (@NotBlank on name)
        if (bindingResult.hasErrors()) {
            repopulateAccountInfoAndPasswordForm(email, model);
            return "member/account";
        }

        // 2. 정보 수정 도메인 로직 실행 (facade 경유)
        accountFacade.updateProfile(email, form.getName(), form.getPhone());

        // 3. 성공 → /account?profile 로 redirect (PRG 패턴)
        ra.addFlashAttribute("flashSuccess", "회원 정보가 수정되었습니다.");
        return "redirect:/account?profile";
    }

    // ============================================================
    // POST /account/withdraw — 탈퇴 처리
    // ============================================================

    /**
     * 회원 탈퇴 폼 제출.
     * POST /account/withdraw
     *
     * <p>소프트 삭제 후 HTTP 세션 무효화({@link SecurityContextLogoutHandler}).
     * 세션 무효화로 flash attribute 가 소실되므로 flash 미사용.
     * 완료 안내는 {@code /login?withdraw} 쿼리 파라미터로 표시.
     *
     * @param auth     인증 객체
     * @param request  HTTP 요청 (세션 무효화용)
     * @param response HTTP 응답 (세션 무효화용)
     * @return redirect:/login?withdraw
     */
    @PostMapping("/withdraw")
    public String withdraw(
            Authentication auth,
            HttpServletRequest request,
            HttpServletResponse response) {

        String email = auth.getName();

        // 1. 탈퇴 처리 (소프트 삭제 — refresh 무효화 포함)
        accountFacade.withdraw(email);

        // 2. 현재 HTTP 세션 무효화 (SecurityContextLogoutHandler 기본: invalidateHttpSession=true)
        //    세션 무효화 후 flash attribute 는 소실되므로 미사용 (plan §3-3 보강1)
        new SecurityContextLogoutHandler().logout(request, response, auth);

        // 3. /login?withdraw 로 redirect — param.withdraw 로 탈퇴 완료 안내 표시
        //    홈('/')은 anyRequest().authenticated() 보호 경로이므로 세션 무효화 후 /login으로 귀결.
        //    명시적으로 /login?withdraw 를 지정해 안내 메시지를 확실하게 표시.
        return "redirect:/login?withdraw";
    }

    // ============================================================
    // private helpers
    // ============================================================

    /**
     * 비번 변경 폼의 비밀번호 필드 echo 차단.
     * 검증 실패/현재 비번 불일치 재렌더 시 세 필드를 모두 null로 clear.
     */
    private void clearPasswordFields(PasswordChangeForm form) {
        form.setCurrentPassword(null);
        form.setNewPassword(null);
        form.setNewPasswordConfirm(null);
    }

    /**
     * 재렌더 시 accountInfo + 빈 profileForm 을 모델에 다시 채운다.
     * (비번 변경 폼 실패 재렌더 — profileForm 도 필요)
     */
    private void repopulateModel(String email, Model model) {
        AccountInfo info = accountFacade.getAccountInfo(email);
        model.addAttribute("accountInfo", info);

        ProfileUpdateForm profileForm = new ProfileUpdateForm();
        profileForm.setName(info.name());
        profileForm.setPhone(info.phone());
        model.addAttribute("profileForm", profileForm);
    }

    /**
     * 재렌더 시 accountInfo + 빈 passwordForm 을 모델에 다시 채운다.
     * (정보 수정 폼 실패 재렌더 — passwordForm 도 필요)
     */
    private void repopulateAccountInfoAndPasswordForm(String email, Model model) {
        AccountInfo info = accountFacade.getAccountInfo(email);
        model.addAttribute("accountInfo", info);
        model.addAttribute("passwordForm", new PasswordChangeForm());
    }
}
