package com.shop.shop.product.spi;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 구매 단위(variant) 표시·검증 전용 published port (product 소유).
 *
 * <p>cart 모듈이 product 내부 Entity/Repository/Service를 직접 참조하지 않도록
 * product가 소유·구현하는 얇은 포트. Entity 노출 금지 — record(scalar)만 반환.
 *
 * <p>구현체는 product 내부 {@code service} 패키지에 배치한다.
 * 의존 방향: cart → product.spi 단방향.
 */
public interface ProductPurchaseCatalog {

    /**
     * 단건 variant 구매가능성·표시정보 조회 (담기·수량변경 검증용).
     *
     * <p>미존재 variantId: 빈 Optional 대신 purchasable=false인 PurchasableVariant를 반환하거나
     * 구현체가 {@link com.shop.shop.common.exception.VariantNotPurchasableException}을 던질 수 있다.
     * cart는 purchasable=false 또는 예외 모두 400으로 처리한다.
     *
     * @param variantId 조회할 variant ID
     * @return PurchasableVariant (미존재 시 purchasable=false 또는 예외)
     */
    PurchasableVariant getPurchasableVariant(long variantId);

    /**
     * IN 배치 variant 구매가능성·표시정보 조회 (장바구니 조회 합성용, N+1 회피).
     *
     * <p>존재하는 variantId만 반환한다. 목록에 없는 variantId(삭제됨)는 cart가 available=false 폴백으로 처리한다.
     *
     * @param variantIds 조회할 variant ID 컬렉션
     * @return 존재하는 variant의 PurchasableVariant 목록
     */
    List<PurchasableVariant> getPurchasableVariants(Collection<Long> variantIds);

    /**
     * 구매 단위 표시·검증 DTO.
     *
     * <p>cart는 purchasable/stock(판정)·price/productName/optionLabel/imageUrl(표시)만 사용한다.
     * stock은 cart 내부 판정·atomic UPDATE 파라미터 전용, 외부 응답에 노출하지 않는다.
     *
     * @param variantId     variant ID
     * @param productId     상품 ID
     * @param productName   상품명
     * @param productStatus 상품 상태 String (purchasable에 이미 반영, 표시 보조용)
     * @param optionLabel   variant 옵션 라벨 (예: "빨강 / L")
     * @param imageUrl      상품 대표 이미지 URL (없으면 null)
     * @param price         현재 variant 가격
     * @param active        variant 활성 여부
     * @param stock         현재 재고 수치 (cart 내부 판정 전용, 외부 미노출)
     * @param purchasable   구매가능 = (productStatus==ON_SALE && active)
     */
    record PurchasableVariant(
            long variantId,
            long productId,
            String productName,
            String productStatus,
            String optionLabel,
            String imageUrl,
            BigDecimal price,
            boolean active,
            int stock,
            boolean purchasable
    ) {
    }
}
