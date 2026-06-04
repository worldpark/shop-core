/**
 * 장바구니 모듈 — DTO 레이어.
 *
 * <p>요청·응답 DTO 클래스를 위치시킨다.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Request DTO: 검증 어노테이션 필수({@code @NotBlank}, {@code @NotNull}, {@code @Size}).</li>
 *   <li>Response DTO: 정적 팩토리 메서드 {@code of(Entity entity)} 패턴 사용.</li>
 *   <li>Record 또는 {@code @Getter} + {@code @NoArgsConstructor} 사용.</li>
 * </ul>
 */
package com.shop.shop.cart.dto;
