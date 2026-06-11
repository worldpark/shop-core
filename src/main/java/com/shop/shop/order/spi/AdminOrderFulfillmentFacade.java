package com.shop.shop.order.spi;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.dto.AdminOrderFulfillmentView;
import com.shop.shop.order.dto.ShipmentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 관리자 주문 이행 View 전용 facade (published port).
 *
 * <p>{@code web} 모듈의 {@code AdminOrderViewController}가 order 도메인 내부 Service·Entity를 직접
 * 참조하지 않도록 이 facade를 경유한다. 구현체는 order 내부 {@code service} 패키지에 위치한다.
 *
 * <p>의존 방향: web → order.spi (단방향). order는 web을 참조하지 않는다.
 *
 * @see AdminMemberFacade 선례 패턴 참조
 */
public interface AdminOrderFulfillmentFacade {

    /**
     * 이행 대상 주문 목록 조회.
     *
     * <p>{@code paid}/{@code preparing} 상태의 주문 + 미발송 항목 + 기존 배송 현황을 반환한다.
     * View 목록 렌더링 및 배송 생성 폼 노출 조건 판단에 사용한다.
     *
     * @param pageable 페이지 요청
     * @return {@link AdminOrderFulfillmentView} DTO 페이지 (Entity 미노출)
     */
    Page<AdminOrderFulfillmentView> listFulfillableOrders(Pageable pageable);

    /**
     * 배송 생성 위임.
     *
     * <p>{@link com.shop.shop.order.service.OrderFulfillmentService#createShipment}에 위임한다.
     * 성공 시 {@link ShipmentResponse} 반환, 거부 시 {@link BusinessException}(400/409) 전파.
     *
     * @param orderId      대상 주문 ID
     * @param orderItemIds 포함할 주문 항목 ID 목록 (null/빈 목록 = 미발송 항목 전부)
     * @return 생성된 배송 응답 DTO
     * @throws BusinessException 상태 충돌(409) 또는 입력 오류(400)
     */
    ShipmentResponse createShipment(long orderId, List<Long> orderItemIds);
}
