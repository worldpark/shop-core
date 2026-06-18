package com.shop.shop.member.service;

import lombok.extern.slf4j.Slf4j;

/**
 * [054 비활성화] formLogin 로그인 성공 이벤트 리스너 — last_login_at 기록.
 *
 * <p>Task 054: View 인증이 formLogin → JWT 쿠키로 cutover되면서
 * {@link org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent}가
 * 더 이상 발행되지 않는다 (formLogin의 UsernamePasswordAuthenticationFilter가 제거됨).
 *
 * <p>last_login_at 기록은 두 경로에서 명시 호출로 대체됨:
 * <ul>
 *   <li>REST 로그인: {@link AuthServiceResponse#login} → {@link MemberService#recordLoginByEmail}</li>
 *   <li>View 로그인: {@link ViewAuthService#loginAndSetCookies} → {@link MemberService#recordLoginByEmail}</li>
 * </ul>
 *
 * <p>이 클래스는 @Component 해제 → 스프링 빈 미등록 → 이벤트 리스너 비활성화.
 * 클래스 자체는 회귀 가드 테스트 참조를 위해 유지한다.
 */
@Slf4j
public class LoginActivityRecorder {

    /**
     * 비활성화됨 — formLogin 제거로 이 이벤트가 발행되지 않는다 (054 cutover).
     * last_login_at은 ViewAuthService / AuthServiceResponse 로그인 경로의
     * recordLoginByEmail 명시 호출로 보장된다.
     */
    public void onInteractiveAuthenticationSuccess(
            org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent event) {
        // 비활성화: formLogin 제거로 이 메서드는 호출되지 않는다.
        log.warn("LoginActivityRecorder.onInteractiveAuthenticationSuccess 호출됨 — 이 경로는 054에서 제거 예정");
    }
}
