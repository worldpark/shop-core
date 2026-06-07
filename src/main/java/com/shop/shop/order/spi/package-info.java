/**
 * order 모듈의 published port (SPI).
 *
 * <p>이 패키지는 Spring Modulith @NamedInterface("spi")로 노출된 published port 경계다.
 * web 모듈의 OrderViewController가 order 도메인 내부 Service·Entity·enum을 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 order 내부 {@code service} 패키지에 위치한다.
 *
 * <p>구현체는 order 내부의 비공개 {@code service} 패키지에 배치한다 (spi 패키지에는 인터페이스만).
 */
@org.springframework.modulith.NamedInterface("spi")
package com.shop.shop.order.spi;
