package com.shop.shop.order.adapter;

import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.PurchaseVerificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link PurchaseVerificationPort} 구현체 (order 모듈 어댑터 — 의존 역전).
 *
 * <p>order 모듈이 소유하는 어댑터다. product 모듈 소유 포트 {@link PurchaseVerificationPort}를 구현한다.
 * 내부에서 order_item + order(userId·status) 조회를 수행하고, variant→productId는
 * {@link ProductOrderCatalog}(기존 포트 재사용)로 해석한다.
 *
 * <p>의존 방향: order → product.spi(@NamedInterface) 단방향.
 * product는 order를 전혀 참조하지 않는다.
 *
 * <p>Entity를 product로 노출하지 않음 — {@link PurchaseVerification}만 반환.
 *
 * <p>IDOR 보장: order_item 미존재 및 order.userId != userId 모두 ownedAndExists=false로 단일 처리.
 */
@Component
@RequiredArgsConstructor
public class OrderPurchaseVerificationAdapter implements PurchaseVerificationPort {

    private final OrderItemQueryRepository orderItemQueryRepository;
    private final ProductOrderCatalog productOrderCatalog;

    /**
     * {@inheritDoc}
     *
     * <p>order_item + order(userId·status) 단건 projection 조회.
     * <ul>
     *   <li>조회 실패(미존재·타인 소유) → ownedAndExists=false, delivered/productId 무의미값</li>
     *   <li>조회 성공 → delivered = "delivered".equals(status), productId = variant 해석 결과</li>
     * </ul>
     */
    @Override
    @Transactional(readOnly = true)
    public PurchaseVerification verify(long orderItemId, long userId) {
        return orderItemQueryRepository.findOrderItemProjection(orderItemId)
                .map(proj -> {
                    // 소유권 검증 (IDOR 방어: 미존재와 타인 소유 동일 처리)
                    if (proj.userId() != userId) {
                        return new PurchaseVerification(false, false, null);
                    }

                    boolean delivered = "delivered".equals(proj.status());
                    Long productId = resolveProductId(proj.variantId());
                    return new PurchaseVerification(true, delivered, productId);
                })
                .orElse(new PurchaseVerification(false, false, null));
    }

    /**
     * variantId → productId 해석.
     *
     * <p>variantId가 null(ON DELETE SET NULL)이면 productId=null(도출 불가).
     * non-null variantId는 ProductOrderCatalog.getOrderableSnapshots로 해석한다.
     * snapshot 빔(방어 경로 — 거의 도달 불가, §2.9 참조) → null 반환으로 안전 변환.
     *
     * @param variantId variant ID (nullable)
     * @return 상품 ID (null = 도출 불가)
     */
    private Long resolveProductId(Long variantId) {
        if (variantId == null) {
            return null;
        }
        List<ProductOrderCatalog.OrderableVariantSnapshot> snapshots =
                productOrderCatalog.getOrderableSnapshots(List.of(variantId));
        return snapshots.isEmpty() ? null : snapshots.get(0).productId();
    }
}
