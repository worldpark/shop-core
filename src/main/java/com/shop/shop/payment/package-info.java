/**
 * 결제(payment) 도메인 모듈.
 *
 * <p>결제 요청·승인·실패·환불을 담당한다.
 * REST API({@code /api/v1/payments}) 및 관련 화면을 소유한다.
 *
 * <p>결제 실패 시 {@code PaymentFailedEvent}를, 배송 시작 시 {@code ShippingStartedEvent}를
 * 발행한다({@code messaging} 서브패키지 → Kafka).
 * 이벤트 계약 SSOT: {@code docs/architecture.md} 섹션 5.
 *
 * <p>모듈 간 직접 의존 금지. 협력은 도메인 이벤트 또는 명시 노출 API 타입으로만.
 */
package com.shop.shop.payment;
