/**
 * order 모듈 SPI 전용 DTO 패키지.
 *
 * <p>이 패키지는 Spring Modulith @NamedInterface("spi")로 노출된 published port의 DTO를 담는다.
 * 외부 모듈(web 등)은 이 패키지의 DTO를 통해 order SPI 결과를 받는다.
 *
 * <p>의존 방향: web → order.spi.dto (단방향). order는 web/product를 참조하지 않는다.
 */
@org.springframework.modulith.NamedInterface("spi")
package com.shop.shop.order.spi.dto;
