/**
 * 상품 모듈 — 컨트롤러 레이어.
 *
 * <p>REST 진입점만 담당한다: {@code @RestController → ServiceResponse → Service → Repository}.
 * SSR 화면(ViewController)은 web 지원 모듈로 분리되었으며(Task 003),
 * 화면 요청은 {@code product.spi}의 View 전용 facade를 통해 이 모듈에 도달한다.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Entity를 API 응답으로 직접 반환하지 않는다(DTO 변환 필수).</li>
 *   <li>비즈니스 로직을 Controller에 작성하지 않는다.</li>
 *   <li>REST 결과는 {@code ResponseEntity<>}로 래핑한다.</li>
 *   <li>URL은 복수 명사 사용: {@code /api/v1/products}, {@code /products}.</li>
 * </ul>
 */
package com.shop.shop.product.controller;
