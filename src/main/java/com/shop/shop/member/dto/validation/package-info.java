/**
 * member 모듈 — 검증 어노테이션/validator 레이어.
 *
 * <p>Bean Validation 제약 어노테이션({@code @PasswordMatches})과 구현체를 위치시킨다.
 *
 * <p>이 패키지는 Spring Modulith {@code @NamedInterface("validation")}으로 노출된다.
 * 외부 모듈(web 등)이 {@code @PasswordMatches} 어노테이션을 Form 클래스에 직접 선언할 수 있도록
 * 공개 인터페이스로 노출한다. 어노테이션은 컴파일 타임 타입 참조이므로 facade가 아닌 직접 노출이 필요하다.
 */
@org.springframework.modulith.NamedInterface("validation")
package com.shop.shop.member.dto.validation;
