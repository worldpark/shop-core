/**
 * 공통(common) 모듈 — OPEN 공유 모듈.
 *
 * <p>모든 도메인 모듈이 의존하는 횡단 공유 컴포넌트를 제공한다:
 * <ul>
 *   <li>{@code domain.BaseEntity} — 공통 감사 필드(createdAt, updatedAt)</li>
 *   <li>{@code exception.BusinessException} — 비즈니스 예외 기반 클래스</li>
 *   <li>{@code exception.ErrorResponse} — REST API 공통 에러 응답 포맷</li>
 *   <li>{@code exception.RestExceptionHandler} — REST 전역 예외 핸들러</li>
 *   <li>{@code exception.ViewExceptionHandler} — View 전역 예외 핸들러</li>
 *   <li>{@code config.RedisConfig} — Redis StringRedisTemplate 설정</li>
 * </ul>
 *
 * <p>OPEN 모듈로 선언하여 다른 모듈이 서브패키지(internal) 타입을 참조해도
 * Spring Modulith verify 위반으로 잡지 않는다.
 * 도메인 모듈은 {@code common} 내부 타입에 자유롭게 의존할 수 있다.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.shop.shop.common;
