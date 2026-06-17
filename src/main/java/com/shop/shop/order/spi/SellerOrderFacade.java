package com.shop.shop.order.spi;

import com.shop.shop.order.spi.dto.SellerOrderView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 판매자 주문 조회 facade (published port — order 소유).
 *
 * <p>판매자가 자기 owner_id 항목이 포함된 주문 목록을 최신순으로 조회한다.
 * 응답에는 그 판매자 소유 항목만 포함되며, 타 판매자 항목은 제외된다(소유권 스코핑 — IDOR 방지).
 *
 * <p>email → sellerId(= userId = owner_id) 해석은 facade 구현체 내부에서
 * {@code member.spi.MemberDirectory.findUserIdByEmail}로 수행한다.
 * ({@link OrderFacade#getMyOrders(String, Pageable)} 선례와 동일 패턴).
 * web은 actor.email()만 전달하며 email→id 변환을 직접 수행하지 않는다.
 *
 * <p>소유권 검사: ADMIN 특례 없음 — 판매자 본인 owner_id 기준 조회.
 * ({@code product.spi.SellerProductFacade} "ADMIN 특례 없음" 선례 동일).
 *
 * <p>Phase 1: 읽기 전용. 배송 쓰기(생성/시작/완료)는 Phase 2(049).
 *
 * <p>의존 방향: web → order.spi 단방향. order는 web을 참조하지 않는다.
 */
public interface SellerOrderFacade {

    /**
     * 판매자 주문 목록 조회 (최신순 페이지네이션).
     *
     * <p>actorEmail → sellerId 해석 후 {@code order_items.owner_id = sellerId}인 주문만 반환한다.
     * 각 주문 응답에는 해당 판매자 소유 항목만 포함(타 판매자 항목 제외).
     * 항목별 배송 상태는 {@code shipment_items → shipments} 배치 조회로 N+1 없이 조립한다.
     *
     * @param actorEmail form-login principal email (facade 내부에서 sellerId로 해석)
     * @param pageable   페이지 요청
     * @return 판매자 소유 항목이 포함된 주문 페이지 (최신순, 항목별 배송 상태 포함)
     */
    Page<SellerOrderView> listSellerOrders(String actorEmail, Pageable pageable);
}
