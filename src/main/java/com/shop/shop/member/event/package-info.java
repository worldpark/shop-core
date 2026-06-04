/**
 * 회원 모듈 — 도메인 이벤트 레이어.
 *
 * <p>이 모듈에서 발행하는 도메인 이벤트 클래스를 위치시킨다.
 * Spring Modulith Transactional Outbox(Event Publication Registry)를 통해 발행한다.
 *
 * <p>가드레일:
 * <ul>
 *   <li>이벤트 계약 변경 시 코드보다 {@code docs/architecture.md}를 먼저 수정한다.</li>
 *   <li>페이로드는 자족적으로 구성한다(컨슈머가 재조회하지 않도록).</li>
 *   <li>모든 이벤트는 {@code eventId}와 발생 시각을 포함한다(멱등·추적용).</li>
 * </ul>
 */
package com.shop.shop.member.event;
