/**
 * 주문 모듈 — 도메인(Entity) 레이어.
 *
 * <p>JPA Entity 클래스를 위치시킨다.
 * {@code @Entity}, {@code @Table(name = "snake_case")} 적용.
 * {@code BaseEntity}(createdAt, updatedAt) 상속.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Setter 사용 금지. 생성자 또는 정적 팩토리 메서드({@code of(...)}) 사용.</li>
 *   <li>Entity를 API 응답으로 직접 반환하지 않는다.</li>
 * </ul>
 */
package com.shop.shop.order.domain;
