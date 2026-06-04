/**
 * 주문 모듈 — 서비스 레이어.
 *
 * <p>비즈니스 로직을 담당한다. {@code @Service}, {@code @Transactional} 적용.
 * 조회 메서드는 {@code @Transactional(readOnly = true)} 사용.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Repository를 직접 호출하는 Service 함수를 통해 데이터에 접근한다.</li>
 *   <li>Entity를 Controller에 직접 반환하지 않는다(DTO 변환 필수).</li>
 *   <li>모든 예외는 {@code RuntimeException} 상속 커스텀 예외로 변환한다.</li>
 * </ul>
 */
package com.shop.shop.order.service;
