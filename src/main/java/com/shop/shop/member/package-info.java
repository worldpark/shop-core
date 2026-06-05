/**
 * 회원(member) 도메인 모듈.
 *
 * <p>인증 주체(회원 엔티티·프로필·가입·탈퇴)를 소유하고, 화면용 published API({@code member.spi} facade)와 DTO를 제공한다.
 * 로그인·회원가입·관리자 회원 관리 등 SSR 화면 진입점(ViewController)은 web 지원 모듈로 분리되어 있으며(Task 003),
 * web은 {@code member.spi}·{@code member.dto} named interface만 의존한다.
 *
 * <p>향후 DB 기반 {@code UserDetailsService} 구현이 이 모듈에 위치하며,
 * {@code security} 모듈의 {@code SecurityConfig}는 해당 구현을 인터페이스(DI)로 주입받는다.
 * 직접 타입 의존 없이 느슨하게 결합한다.
 *
 * <p>모듈 간 직접 의존 금지. 협력은 도메인 이벤트 또는 명시 노출 API 타입으로만.
 */
package com.shop.shop.member;
