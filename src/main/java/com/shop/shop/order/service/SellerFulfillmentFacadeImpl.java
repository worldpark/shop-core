package com.shop.shop.order.service;

import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link SellerFulfillmentFacade} 구현체 (package-private).
 *
 * <p>order 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link SellerFulfillmentFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>actorEmail → sellerId 해석 ({@link MemberDirectory#findUserIdByEmail(String)})</li>
 *   <li>배송 생성: {@link OrderFulfillmentService#createShipmentForSeller} 위임</li>
 *   <li>배송 시작/완료: shipment.seller_id 스칼라 검사 후 {@link OrderFulfillmentService#ship}/{@link OrderFulfillmentService#deliver} 위임</li>
 *   <li>★ stale-read 금지: ship/deliver 위임 전 {@link ShipmentRepository#findSellerIdById} 스칼라만 읽어 소유권 비교.
 *       엔티티 선적재 절대 금지(JPA L1 캐시 stale → 이벤트 중복 발행).</li>
 * </ul>
 *
 * <p>소유권 위반 = 404(존재 은닉 — plan §1.5, ProductAccessDeniedException 선례):
 * 미존재/불일치/null(admin 생성) shipment → {@link ShipmentNotFoundException}(404).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class SellerFulfillmentFacadeImpl implements SellerFulfillmentFacade {

    private final MemberDirectory memberDirectory;
    private final ShipmentRepository shipmentRepository;
    private final OrderFulfillmentService orderFulfillmentService;

    /**
     * {@inheritDoc}
     *
     * <p>actorEmail → sellerId 해석 후 {@link OrderFulfillmentService#createShipmentForSeller} 위임.
     * BusinessException은 변환 없이 전파(web이 catch → flashError 또는 REST 에러 응답).
     */
    @Override
    @Transactional
    public ShipmentResponse createShipment(String actorEmail, long orderId, List<Long> orderItemIds) {
        long sellerId = memberDirectory.findUserIdByEmail(actorEmail);
        return orderFulfillmentService.createShipmentForSeller(orderId, orderItemIds, sellerId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>★ stale-read 금지 — 소유권 검사 순서:
     * <ol>
     *   <li>{@link ShipmentRepository#findSellerIdById} 스칼라만 조회 (엔티티 선적재 금지)</li>
     *   <li>미존재(empty) → 404</li>
     *   <li>seller_id null(admin 생성) 또는 불일치 → 404(존재 은닉)</li>
     *   <li>통과 시 {@link OrderFulfillmentService#ship} 위임 (내부에서 락 후 fresh findById)</li>
     * </ol>
     */
    @Override
    @Transactional
    public ShipmentResponse ship(String actorEmail, long shipmentId, String carrier, String trackingNumber) {
        long sellerId = memberDirectory.findUserIdByEmail(actorEmail);
        verifySellerOwnership(shipmentId, sellerId, "ship");
        return orderFulfillmentService.ship(shipmentId, carrier, trackingNumber);
    }

    /**
     * {@inheritDoc}
     *
     * <p>★ stale-read 금지 — ship 과 동일한 스칼라 소유권 검사 후 {@link OrderFulfillmentService#deliver} 위임.
     */
    @Override
    @Transactional
    public DeliverResponse deliver(String actorEmail, long shipmentId) {
        long sellerId = memberDirectory.findUserIdByEmail(actorEmail);
        verifySellerOwnership(shipmentId, sellerId, "deliver");
        return orderFulfillmentService.deliver(shipmentId);
    }

    /**
     * 스칼라 projection으로 sellerId 일치 여부 확인 (엔티티 선적재 금지 — stale-read 가드).
     *
     * <p>Spring Data JPA에서 JPQL 스칼라 null은 Optional.empty()로 반환된다.
     * 따라서 {@link ShipmentRepository#findSellerIdById}가 empty를 반환하는 경우는 두 가지:
     * (a) shipment 행 미존재, (b) shipment.seller_id = null (admin 생성 배송).
     * 둘 다 판매자 관점에서 "접근 불가" → 404(존재 은닉).
     * {@link ShipmentRepository#findOrderIdAndSellerIdById}로 존재/null 구분 후 로그만 다르게 처리한다.
     *
     * @param shipmentId  배송 ID
     * @param sellerId    요청 판매자 ID
     * @param operation   로그용 작업명 (ship/deliver)
     * @throws ShipmentNotFoundException 미존재·seller_id null(admin 생성)·소유권 불일치 (404)
     */
    private void verifySellerOwnership(long shipmentId, long sellerId, String operation) {
        // ★ 엔티티 적재 없이 스칼라만 조회 — JPA L1 캐시 오염 방지(stale-read 가드)
        // JPQL 스칼라 null → Optional.empty() (Spring Data JPA 동작)
        Long storedSellerId = shipmentRepository.findSellerIdById(shipmentId).orElse(null);

        if (storedSellerId == null) {
            // (a) 미존재 또는 (b) seller_id=null (admin 생성) — findOrderIdAndSellerIdById로 구분(로그용)
            List<Object[]> rows = shipmentRepository.findOrderIdAndSellerIdById(shipmentId);
            if (rows.isEmpty()) {
                log.warn("배송 {} 소유권 검사 — 미존재: shipmentId={}", operation, shipmentId);
            } else {
                log.warn("배송 {} 소유권 검사 — seller_id null(admin 생성): shipmentId={}, sellerId={}",
                        operation, shipmentId, sellerId);
            }
            throw new ShipmentNotFoundException();
        }

        if (storedSellerId != sellerId) {
            log.warn("배송 {} 소유권 불일치 — 존재 은닉 404: shipmentId={}, storedSellerId={}, requestSellerId={}",
                    operation, shipmentId, storedSellerId, sellerId);
            throw new ShipmentNotFoundException();
        }
    }
}
