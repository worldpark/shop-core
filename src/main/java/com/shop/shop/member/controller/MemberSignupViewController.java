package com.shop.shop.member.controller;

import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.member.dto.SignupForm;
import com.shop.shop.member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 회원가입 View 진입점.
 *
 * <p>레이어: ViewController → MemberService → MemberRepository (ServiceResponse 미사용 — architecture-rule).
 * 모델엔 DTO/ViewModel만 담는다 (Entity 직접 전달 금지).
 *
 * <p>비밀번호 echo 차단 (§1.3):
 * 검증 실패·이메일 중복 재렌더 시 password/passwordConfirm을 clear — 보안상 echo 금지.
 * 이메일/name/phone은 유지하여 사용자 편의 제공.
 *
 * <p>이메일 중복 처리 (§1.4):
 * MemberService.signup이 DuplicateEmailException을 던지면 BindingResult.rejectValue("email", ...)로
 * email 필드 에러로 변환 후 재렌더 — REST JSON 반환 금지 (error-response-rule: View는 재렌더).
 */
@Controller
@RequiredArgsConstructor
public class MemberSignupViewController {

    private final MemberService memberService;

    /**
     * 회원가입 화면 표시.
     * GET /signup
     *
     * <p>빈 SignupForm 모델 키 "signupForm"으로 model에 추가 → view "member/signup".
     */
    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("signupForm", new SignupForm());
        return "member/signup";
    }

    /**
     * 회원가입 처리.
     * POST /signup
     *
     * <p>CSRF 보호 대상 — Thymeleaf th:action="@{/signup}"으로 _csrf 자동 주입.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>@Valid 검증 실패 → 비번 clear → "member/signup" 재렌더 (필드 에러 표시)</li>
     *   <li>MemberService.signup 호출</li>
     *   <li>DuplicateEmailException → email 필드 에러 바인딩 → 비번 clear → "member/signup" 재렌더</li>
     *   <li>성공 → "redirect:/login?signup"</li>
     * </ol>
     *
     * @param form          폼 백킹 객체 (모델 키 "signupForm")
     * @param bindingResult 검증 결과 (반드시 form 파라미터 바로 다음 위치)
     * @return view name 또는 redirect
     */
    @PostMapping("/signup")
    public String signup(
            @Valid @ModelAttribute("signupForm") SignupForm form,
            BindingResult bindingResult) {

        // 1. Bean Validation 실패 처리 (@Valid — @Email/@NotBlank/@Size/@PasswordMatches)
        if (bindingResult.hasErrors()) {
            clearPasswords(form);   // 비밀번호 원문 echo 차단 (Constraint)
            return "member/signup";
        }

        // 2. 회원가입 도메인 로직 실행 (MemberService — 비즈니스 로직은 Service에)
        try {
            memberService.signup(form.getEmail(), form.getPassword(), form.getName(), form.getPhone());
        } catch (DuplicateEmailException e) {
            // 3. 이메일 중복 — email 필드 에러로 변환 후 재렌더 (JSON 반환 금지)
            bindingResult.rejectValue("email", "duplicate", "이미 사용 중인 이메일입니다.");
            clearPasswords(form);   // 비밀번호 원문 echo 차단
            return "member/signup";
        }

        // 4. 성공 → /login?signup 으로 redirect (PRG 패턴)
        return "redirect:/login?signup";
    }

    /**
     * 비밀번호 필드 echo 차단.
     * 검증 실패 재렌더 시 password/passwordConfirm 값을 null로 clear.
     * 이메일/name/phone은 유지 (사용자 편의).
     */
    private void clearPasswords(SignupForm form) {
        form.setPassword(null);
        form.setPasswordConfirm(null);
    }
}
