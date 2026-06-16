package com.shop.shop.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * formLogin 로그인 성공 이벤트 리스너 — last_login_at 기록.
 *
 * <p>{@link InteractiveAuthenticationSuccessEvent}는 {@code AbstractAuthenticationProcessingFilter}
 * (= formLogin의 {@code UsernamePasswordAuthenticationFilter})가 인증 성공 직후 발행한다.
 * REST 수동 authenticate·JwtAuthenticationFilter는 이 필터를 거치지 않으므로 이 이벤트를 발행하지
 * 않는다 → formLogin에만 발동(plan §3.3, 이중 기록 없음).
 *
 * <p>SecurityConfig·successHandler·requestCache·defaultSuccessUrl를 변경하지 않는다.
 * member 모듈 내부(spring-security 프레임워크 이벤트 타입 + 같은 모듈 MemberService)만 의존하므로
 * Spring Modulith 모듈 경계 위반 없음.
 *
 * <p>Clock 빈 의존 금지(plan §0 BLOCKER): Instant.now()는 MemberService.recordLoginByEmail 내부에서 호출.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginActivityRecorder {

    private final MemberService memberService;

    /**
     * formLogin 인증 성공 이벤트 수신 → last_login_at 갱신.
     *
     * <p>{@code event.getAuthentication().getName()}은 Spring Security가 UserDetailsService에서 반환한
     * UserDetails.getUsername() 값이며, 이 프로젝트에서는 회원 이메일이다.
     *
     * @param event formLogin UsernamePasswordAuthenticationFilter가 발행한 성공 이벤트
     */
    @EventListener
    public void onInteractiveAuthenticationSuccess(InteractiveAuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        log.debug("formLogin 인증 성공 이벤트 수신: email={}", email);
        memberService.recordLoginByEmail(email);
    }
}
