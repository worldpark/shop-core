/**
 * 회원 모듈 — 컨트롤러 레이어.
 *
 * <p>REST 진입점: {@code @RestController → ServiceResponse → Service → Repository}.
 * View 진입점: {@code @Controller (ViewController) → Service → Repository}.
 *
 * <p>현재 {@link com.shop.shop.member.controller.LoginViewController}가 {@code /login} 화면을 담당한다.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Entity를 API 응답으로 직접 반환하지 않는다(DTO 변환 필수).</li>
 *   <li>비즈니스 로직을 Controller에 작성하지 않는다.</li>
 *   <li>REST 결과는 {@code ResponseEntity<>}로 래핑한다.</li>
 * </ul>
 */
package com.shop.shop.member.controller;
