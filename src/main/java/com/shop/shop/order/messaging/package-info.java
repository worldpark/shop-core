/**
 * 주문 모듈 — 메시징(Kafka Producer) 레이어.
 *
 * <p>Kafka Producer를 위치시킨다. 주문 확정 시 {@code OrderCompletedEvent}를 Kafka로 발행하는
 * Producer가 향후 이 패키지에 위치한다(이벤트 페이로드 클래스는 계약 확정 후 생성, 현 Task 범위 밖).
 *
 * <p>이벤트 계약 SSOT: {@code docs/architecture.md} 섹션 5.
 *
 * <p>가드레일:
 * <ul>
 *   <li>변경은 가산적(필드 추가) 우선. 필드 삭제·타입 변경은 호환성 검토 후에만.</li>
 *   <li>Spring Modulith Event Publication Registry(Transactional Outbox)와 연동한다.</li>
 * </ul>
 */
package com.shop.shop.order.messaging;
