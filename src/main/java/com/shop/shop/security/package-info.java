/**
 * security 지원 모듈 — JWT 인증 인프라 횡단 모듈.
 *
 * <p>역할: JWT 발급·파싱·검증, Redis refresh/blacklist, Spring Security 필터체인 구성.
 *
 * <p>의존: common(OPEN) 모듈만 직접 참조한다.
 * member 도메인 타입(User, MemberUserDetailsService)은 런타임 DI(UserDetailsService 인터페이스)로만 주입받아
 * 컴파일 타임 모듈 간 의존을 차단한다.
 *
 * <p>OPEN 모듈로 선언: security 내부 타입(JwtProperties, JwtTokenProvider 등)을
 * member 등 다른 모듈이 참조해야 하므로 내부 타입 노출을 허용한다.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.shop.shop.security;
