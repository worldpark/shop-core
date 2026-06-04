/**
 * 장바구니(cart) 도메인 모듈.
 *
 * <p>회원별 장바구니 추가·수정·삭제·조회를 담당한다.
 * REST API({@code /api/v1/cart}) 및 Thymeleaf 장바구니 화면({@code /cart})을 소유한다.
 *
 * <p>모듈 간 직접 의존 금지. 협력은 도메인 이벤트 또는 명시 노출 API 타입으로만.
 */
package com.shop.shop.cart;
