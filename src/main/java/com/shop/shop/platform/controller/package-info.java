/**
 * platform 모듈 — 컨트롤러 레이어.
 *
 * <p>REST 진입점: {@code @RestController → ServiceResponse → Service}.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Controller에 비즈니스 로직 작성 금지. {@code ServiceResponse}에만 위임한다.</li>
 *   <li>Entity·이벤트를 API 응답으로 직접 반환하지 않는다(DTO 변환 필수).</li>
 *   <li>REST 결과는 {@code ResponseEntity<>}로 래핑한다.</li>
 *   <li>URL은 {@code /api/v1/**} 패턴을 준수한다.</li>
 * </ul>
 */
package com.shop.shop.platform.controller;
