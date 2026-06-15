package com.shop.shop.product.dto;

import com.shop.shop.product.domain.Product;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 판매자 본인 상품 목록 행 View DTO (읽기 전용).
 *
 * <p><b>{@link PublicProductSummaryResponse}와의 구분</b>: 이 DTO는 소유자 전용 화면에서만 사용한다.
 * 공개 DTO({@link PublicProductSummaryResponse})는 집계 displayPrice·구매가능 variant·공개 status만 노출하는 반면,
 * 이 DTO는 basePrice(등록가)·전체 status(DRAFT/HIDDEN 포함)를 노출한다. 두 DTO를 혼용하지 않는다.
 *
 * <p>status는 {@code ProductStatus.name()} 문자열로 노출한다. web이 {@code ProductStatus} enum을
 * 직접 참조하지 않도록 한다(기존 ProductFormView·productStatusNames() 원칙과 동일).
 *
 * <p>Entity 직접 노출 금지 — {@link #from(Product)} 정적 팩토리로만 생성한다.
 *
 * @param productId  상품 ID
 * @param name       상품명
 * @param status     상품 상태 문자열 (DRAFT/ON_SALE/SOLD_OUT/HIDDEN) — web이 enum 비참조
 * @param basePrice  판매자 등록가 (공개 displayPrice와 구분 — 소유자 화면 전용)
 * @param createdAt  등록일시 (Instant — 표현은 템플릿에서 KST 변환)
 */
public record SellerProductSummaryView(
        long productId,
        String name,
        String status,
        BigDecimal basePrice,
        Instant createdAt
) {

    /**
     * {@code Product} Entity로부터 판매자 목록 행 View DTO 생성.
     *
     * <p>status는 {@code ProductStatus.name()}(대문자 문자열)으로 변환.
     * createdAt은 {@code BaseEntity.getCreatedAt()}에서 읽는다.
     *
     * @param product 상품 Entity (facade 경계 내부에서만 호출 — 모듈 밖 유출 금지)
     * @return SellerProductSummaryView DTO
     */
    public static SellerProductSummaryView from(Product product) {
        return new SellerProductSummaryView(
                product.getId(),
                product.getName(),
                product.getStatus().name(),
                product.getBasePrice(),
                product.getCreatedAt()
        );
    }
}
