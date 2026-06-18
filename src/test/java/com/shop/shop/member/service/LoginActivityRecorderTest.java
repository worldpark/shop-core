package com.shop.shop.member.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LoginActivityRecorder 비활성화 확인 테스트 (054 cutover).
 *
 * <p>054 변경: LoginActivityRecorder는 @Component 해제 → 스프링 빈 미등록.
 * InteractiveAuthenticationSuccessEvent는 formLogin 제거로 더 이상 발행되지 않는다.
 * last_login_at은 ViewAuthService / AuthServiceResponse 로그인 경로의
 * recordLoginByEmail 명시 호출로 보장된다.
 *
 * <p>이 테스트는 LoginActivityRecorder가 @Component가 없는 일반 클래스임을 확인하는
 * 회귀 가드 역할을 한다.
 */
class LoginActivityRecorderTest {

    @Test
    @DisplayName("054 회귀 가드: LoginActivityRecorder는 @Component 없는 일반 클래스 — 스프링 빈 미등록 확인")
    void loginActivityRecorder_is_not_spring_component() {
        // @Component, @Service, @Bean 애노테이션 부재 확인
        Class<LoginActivityRecorder> clazz = LoginActivityRecorder.class;

        org.assertj.core.api.Assertions.assertThat(
                clazz.isAnnotationPresent(org.springframework.stereotype.Component.class)
        ).isFalse();

        org.assertj.core.api.Assertions.assertThat(
                clazz.isAnnotationPresent(org.springframework.stereotype.Service.class)
        ).isFalse();
    }

    @Test
    @DisplayName("054 회귀 가드: onInteractiveAuthenticationSuccess 메서드는 @EventListener 없음")
    void onInteractiveAuthenticationSuccess_has_no_event_listener_annotation() throws NoSuchMethodException {
        var method = LoginActivityRecorder.class.getDeclaredMethod(
                "onInteractiveAuthenticationSuccess",
                org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent.class
        );
        org.assertj.core.api.Assertions.assertThat(
                method.isAnnotationPresent(org.springframework.context.event.EventListener.class)
        ).isFalse();
    }
}
