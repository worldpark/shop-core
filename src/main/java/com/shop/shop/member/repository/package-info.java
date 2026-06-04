/**
 * 회원 모듈 — 리포지토리 레이어.
 *
 * <p>영속화를 담당한다. {@code JpaRepository<Entity, ID>} 상속.
 * 복잡한 쿼리는 {@code @Query} 또는 QueryDSL 사용.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Repository는 Service에서만 호출한다(Consumer·Scheduler 직접 호출 금지).</li>
 *   <li>메서드명 네이밍 컨벤션 준수: {@code findByXxx}, {@code existsByXxx}.</li>
 * </ul>
 */
package com.shop.shop.member.repository;
