/**
 * payment 모듈의 published port (SPI).
 *
 * <p>이 패키지는 Spring Modulith @NamedInterface("spi")로 노출된 published port 경계다.
 * web 모듈의 결제 ViewController가 payment 도메인 내부 Service·Entity를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 payment 내부 {@code service} 패키지에 위치한다.
 *
 * <p>포트 목록:
 * <ul>
 *   <li>{@link com.shop.shop.payment.spi.PaymentGatewayPort} — 모의 PG 추상화 (교체 가능)</li>
 *   <li>{@link com.shop.shop.payment.spi.PaymentFacade} — View 전용 facade (web → payment.spi 단방향)</li>
 * </ul>
 *
 * <p>published API 시그니처는 web 등 호출자 계층 타입을 받지 않는다.
 * payment 소유 DTO({@link com.shop.shop.payment.dto.PaymentRequest})만 수신한다(architecture-rule #1).
 */
@org.springframework.modulith.NamedInterface("spi")
package com.shop.shop.payment.spi;
