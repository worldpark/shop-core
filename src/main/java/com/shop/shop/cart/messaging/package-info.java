/**
 * 장바구니 모듈 — 메시징(Kafka Producer) 레이어.
 *
 * <p>Kafka Producer를 위치시킨다. 도메인 이벤트를 외부 시스템으로 발행한다.
 * Spring Modulith Event Publication Registry(Transactional Outbox)와 연동한다.
 *
 * <p>가드레일:
 * <ul>
 *   <li>이벤트 계약 SSOT: {@code docs/architecture.md} 섹션 5.</li>
 *   <li>변경은 가산적(필드 추가) 우선. 필드 삭제·타입 변경은 호환성 검토 후에만.</li>
 * </ul>
 */
package com.shop.shop.cart.messaging;
