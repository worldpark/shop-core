/**
 * cart 모듈의 published port (SPI).
 *
 * <p>이 패키지는 Spring Modulith @NamedInterface("spi")로 노출된 published port 경계다.
 * web 모듈은 이 패키지의 facade 인터페이스를 통해서만 cart 도메인을 호출할 수 있다.
 * cart는 web을 전혀 참조하지 않으며, 의존 방향은 web → cart.spi 단방향이다.
 *
 * <p>구현체는 cart 내부의 비공개 {@code service} 패키지에 배치한다 (spi 패키지에는 인터페이스만).
 */
@org.springframework.modulith.NamedInterface("spi")
package com.shop.shop.cart.spi;
