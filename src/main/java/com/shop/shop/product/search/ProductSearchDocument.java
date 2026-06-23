package com.shop.shop.product.search;

import com.shop.shop.product.dto.ProductSearchSnapshotProjection;
import com.shop.shop.product.event.ProductSearchIndexChangedEvent;

import java.math.BigDecimal;

/**
 * Elasticsearch 상품 색인 문서 record.
 *
 * <p>ES 문서 필드와 1:1로 대응한다. Entity를 직접 보유하지 않으며 스칼라/record만 포함한다.
 * 문서 {@code _id}는 {@code productId}(ES index 요청 시 명시).
 *
 * <p>이 record는 product 모듈 내부 전용이다. T4(060) 전량 재색인도 이 타입을 재사용한다.
 *
 * @param productId              상품 PK (ES _id)
 * @param name                   상품명 (korean_nori 분석기)
 * @param description            상품 설명 (nullable, korean_nori 분석기)
 * @param categoryId             카테고리 ID (nullable, 필터용)
 * @param categoryName           카테고리명 (nullable, korean_nori 분석기)
 * @param status                 ProductStatus name (keyword, 읽기 필터용)
 * @param displayPrice           COALESCE(MIN 활성 variant price, basePrice) (scaled_float)
 * @param purchasableVariantCount 활성 AND stock>0 개수 (integer, 읽기에서 >0 판정)
 */
public record ProductSearchDocument(
        long productId,
        String name,
        String description,
        Long categoryId,
        String categoryName,
        String status,
        BigDecimal displayPrice,
        long purchasableVariantCount
) {

    /**
     * {@link ProductSearchIndexChangedEvent}에서 문서를 변환하는 정적 팩토리.
     * 이벤트 페이로드가 이미 자족적 스냅샷이므로 재조회 없이 변환한다.
     *
     * @param event 자족 스냅샷 색인 이벤트
     * @return ES 색인 문서
     */
    public static ProductSearchDocument from(ProductSearchIndexChangedEvent event) {
        return new ProductSearchDocument(
                event.productId(),
                event.name(),
                event.description(),
                event.categoryId(),
                event.categoryName(),
                event.status(),
                event.displayPrice(),
                event.purchasableVariantCount()
        );
    }

    /**
     * {@link ProductSearchSnapshotProjection}에서 문서를 변환하는 정적 팩토리.
     * T4(060) 풀 재색인 잡이 PG 전량 조회 후 ES 문서로 변환할 때 사용한다.
     *
     * <p>status는 {@code ProductStatus} enum → {@link Enum#name()} 변환(String).
     * 나머지 필드는 projection과 1:1 대응.
     *
     * @param snapshot PG 집계 스냅샷 projection
     * @return ES 색인 문서
     */
    public static ProductSearchDocument from(ProductSearchSnapshotProjection snapshot) {
        return new ProductSearchDocument(
                snapshot.productId(),
                snapshot.name(),
                snapshot.description(),
                snapshot.categoryId(),
                snapshot.categoryName(),
                snapshot.status().name(),
                snapshot.displayPrice(),
                snapshot.purchasableVariantCount()
        );
    }
}
