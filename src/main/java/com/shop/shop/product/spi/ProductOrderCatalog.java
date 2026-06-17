package com.shop.shop.product.spi;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 주문 생성 전용 variant 스냅샷 조회 published port (product 소유).
 *
 * <p>order 모듈이 주문 생성 시 variant 정보를 조회하는 포트.
 * {@link ProductPurchaseCatalog}(cart용)와 별개 포트 — 옵션값 목록({@link OrderOptionValue}) 추가 제공.
 *
 * <p>사전검증(advisory)과 락 후 저장용 스냅샷 재조회에 동일 포트를 사용한다.
 * 저장용 값은 반드시 InventoryStockPort.decrease 호출(비관적 락 획득) 이후 재조회 결과를 사용한다.
 *
 * <p>Entity 노출 금지 — record(scalar)만 반환.
 * 의존 방향: order → product.spi 단방향.
 */
public interface ProductOrderCatalog {

    /**
     * IN 배치 variant 주문 가능 스냅샷 조회.
     *
     * <p>존재하는 variantId만 반환한다. 목록에 없는 variantId(삭제됨)는
     * order가 ProductNotPurchasableForOrderException(409)으로 처리한다.
     *
     * @param variantIds 조회할 variant ID 컬렉션
     * @return 존재하는 variant의 OrderableVariantSnapshot 목록
     */
    List<OrderableVariantSnapshot> getOrderableSnapshots(Collection<Long> variantIds);

    /**
     * 주문 생성용 variant 스냅샷 (Entity 미노출, scalar only).
     *
     * <p>purchasable = (productStatus == ON_SALE && active).
     * optionValues: 옵션명·옵션값·정렬순서 목록 (order_item_option_values 저장용).
     * price: 현재 variant 가격 (락 후 재조회 시 권위 있음).
     * ownerId: 상품 소유자(판매자) ID 스칼라 스냅샷 (order_items.owner_id 적재용 — V10).
     *
     * @param variantId     variant ID
     * @param productId     상품 ID
     * @param productName   상품명 스냅샷
     * @param optionLabel   variant 옵션 라벨 (예: "빨강 / L")
     * @param optionValues  옵션값 목록 (order_item_option_values 저장용)
     * @param price         현재 variant 가격
     * @param active        variant 활성 여부
     * @param stock         현재 재고 수치 (사전검증 전용, 외부 미노출)
     * @param productStatus 상품 상태 String
     * @param purchasable   구매가능 = (productStatus==ON_SALE && active)
     * @param ownerId       상품 소유자(판매자) ID 스칼라 (products.owner_id — order_items.owner_id 적재용)
     */
    record OrderableVariantSnapshot(
            long variantId,
            long productId,
            String productName,
            String optionLabel,
            List<OrderOptionValue> optionValues,
            BigDecimal price,
            boolean active,
            int stock,
            String productStatus,
            boolean purchasable,
            Long ownerId
    ) {}

    /**
     * 주문 항목 옵션값 (order_item_option_values 저장용).
     *
     * @param optionName  옵션명 (예: "색상")
     * @param optionValue 옵션값 (예: "빨강")
     * @param sortOrder   정렬 순서
     */
    record OrderOptionValue(String optionName, String optionValue, int sortOrder) {}
}
