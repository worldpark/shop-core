/**
 * 상품(product) 도메인 모듈.
 *
 * <p>상품 등록·수정·삭제·조회, 상품 이미지 저장(ObjectStorage)을 담당한다.
 * REST API({@code /api/v1/products}) 및 Thymeleaf 상품 화면({@code /products})을 소유한다.
 *
 * <p>모듈 간 직접 의존 금지. 협력은 도메인 이벤트 또는 명시 노출 API 타입으로만.
 */
package com.shop.shop.product;
