/**
 * product 모듈의 published port (SPI).
 *
 * <p>이 패키지는 Spring Modulith @NamedInterface("spi")로 노출된 published port 경계다.
 * 외부 모듈(member 등)은 이 패키지의 인터페이스를 구현해 의존 역전을 실현할 수 있다.
 * product는 member를 전혀 참조하지 않으며, 의존 방향은 member → product.spi 단방향이다.
 */
@org.springframework.modulith.NamedInterface("spi")
package com.shop.shop.product.spi;
