/**
 * 주문 모듈 — DTO 레이어.
 *
 * <p>이 패키지는 Spring Modulith {@code @NamedInterface("dto")}로 노출된 공개 DTO 경계다.
 * {@code order.spi.OrderFacade}가 반환/인자로 사용하는 DTO 타입이 이 패키지에 위치하므로,
 * {@code web} 모듈이 facade 메서드 반환값을 다루기 위해 이 패키지를 참조할 수 있다.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Request DTO: 검증 어노테이션 필수({@code @NotBlank}, {@code @NotNull}, {@code @Size}).</li>
 *   <li>Response DTO: 정적 팩토리 메서드 {@code of(Entity entity)} 패턴 사용.</li>
 *   <li>Record 또는 {@code @Getter} + {@code @NoArgsConstructor} 사용.</li>
 *   <li>Entity·ownerId·로컬 경로 미노출.</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("dto")
package com.shop.shop.order.dto;
