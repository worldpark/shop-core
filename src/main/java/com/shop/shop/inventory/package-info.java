/**
 * 재고(inventory) 도메인 모듈.
 *
 * <p>상품별 재고 수량 관리, 재고 차감·복원을 담당한다.
 * REST API({@code /api/v1/inventory})를 소유한다.
 *
 * <p>모듈 간 직접 의존 금지. 협력은 도메인 이벤트 또는 명시 노출 API 타입으로만.
 */
package com.shop.shop.inventory;
