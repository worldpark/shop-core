/**
 * 주문(order) 도메인 모듈.
 *
 * <p>주문 생성·확정·조회·취소를 담당한다.
 * REST API({@code /api/v1/orders}) 및 Thymeleaf 주문 화면({@code /orders})을 소유한다.
 *
 * <p>주문 확정 시 {@code OrderCompletedEvent}를 발행한다({@code messaging} 서브패키지 → Kafka).
 * 이벤트 계약 SSOT: {@code docs/architecture.md} 섹션 5.
 *
 * <p>모듈 간 직접 의존 금지. 협력은 도메인 이벤트 또는 명시 노출 API 타입으로만.
 */
package com.shop.shop.order;
