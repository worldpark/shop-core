/**
 * order 모듈의 adapter 패키지.
 *
 * <p>외부 모듈(product 등)의 spi 포트를 구현하는 어댑터 클래스가 위치한다.
 * order 모듈 내부 구현(domain/repository/service)에만 의존하며,
 * 대상 모듈의 포트(인터페이스)만 구현한다.
 *
 * <p>의존 방향: order → product.spi (@NamedInterface) 단방향.
 * order 내부 Entity·Repository를 외부에 노출하지 않는다.
 */
package com.shop.shop.order.adapter;
