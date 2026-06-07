package com.shop.shop.order.spi;

import com.shop.shop.order.dto.OrderCheckoutResponse;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 주문 View 전용 facade (published port).
 *
 * <p>web 모듈의 OrderViewController가 order 도메인 내부 Service·Entity·enum을 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 order 내부 {@code service} 패키지에 위치한다.
 *
 * <p>email을 인자로 받아 내부에서 {@code member.spi.MemberDirectory}로 userId로 변환한다.
 * REST는 이 facade 미사용 — OrderServiceResponse(auth) 경유.
 *
 * <p>의존 방향: web → order.spi 단방향. order는 web을 참조하지 않는다.
 */
public interface OrderFacade {

    /**
     * 주문서 조회 — 현재 장바구니 기반 합성.
     *
     * <p>현재 장바구니 주문 가능 항목·합계를 합성한 주문서 응답 반환.
     * View checkout 화면용. 주문 생성이 아니므로 재고 락·차감·clearCart 없음.
     *
     * @param email form-login principal email
     * @return 주문서 응답 (장바구니 기반 합성, orderId/orderNumber/status/createdAt 없음)
     */
    OrderCheckoutResponse getCheckout(String email);

    /**
     * 주문 생성.
     *
     * <p>배송지 정보(OrderCreateRequest)를 받아 주문을 생성한다.
     * 성공 시 생성된 주문 상세 반환.
     *
     * @param email   form-login principal email
     * @param request 배송지 정보
     * @return 생성된 주문 상세
     */
    OrderResponse createOrder(String email, OrderCreateRequest request);

    /**
     * 내 주문 목록 조회 (최신순 페이지네이션).
     *
     * @param email    form-login principal email
     * @param pageable 페이지 요청
     * @return 최신순 주문 요약 목록 페이지
     */
    Page<OrderSummaryResponse> getMyOrders(String email, Pageable pageable);

    /**
     * 내 주문 상세 조회.
     *
     * <p>타인/미존재 주문 → {@link com.shop.shop.common.exception.OrderNotFoundException}(404 존재 은닉).
     *
     * @param email   form-login principal email
     * @param orderId 주문 ID
     * @return 주문 상세
     */
    OrderResponse getMyOrder(String email, long orderId);
}
