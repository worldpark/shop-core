package com.shop.shop.product.repository;

import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.ProductSummaryProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 상품 JPA 리포지토리.
 *
 * <p>공개 목록 집계 쿼리 3종(latest/priceAsc/priceDesc)을 제공한다.
 * displayPrice = COALESCE(MIN(활성 variant price), basePrice). GROUP BY p.id.
 * countQuery를 분리해 GROUP BY 페이징의 totalElements 산출 오류를 방지한다.
 *
 * <p>정렬은 JPQL ORDER BY 파라미터화 불가 → 정렬별 메서드 3종으로 분리.
 * Service가 PublicProductSort enum에 따라 메서드를 선택한다.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    // =============================================================
    // 공개 상품 목록 — 최신순 (createdAt DESC, id DESC)
    // =============================================================

    /**
     * 공개 상품 목록 집계 쿼리 — 최신순.
     *
     * <p>GROUP BY p.id, displayPrice = COALESCE(MIN(활성 v.price), p.basePrice).
     * purchasableVariantCount = 활성 && stock>0 variant 개수.
     * keyword/categoryId가 null이면 해당 조건을 건너뜀.
     * status 화이트리스트는 :statuses로 전달.
     *
     * @param statuses   노출 허용 status 목록 (ON_SALE, SOLD_OUT)
     * @param keyword    상품명 부분 일치 검색어 (null이면 전체)
     * @param categoryId 카테고리 ID 필터 (null이면 전체)
     * @param pageable   페이지 정보
     * @return projection page
     */
    @Query(value = """
            SELECT new com.shop.shop.product.dto.ProductSummaryProjection(
                p.id,
                p.name,
                COALESCE(MIN(v.price), p.basePrice),
                c.id,
                c.name,
                p.status,
                SUM(CASE WHEN v.isActive = true AND v.stock > 0 THEN 1L ELSE 0L END)
            )
            FROM Product p
            LEFT JOIN p.category c
            LEFT JOIN ProductVariant v ON v.product = p AND v.isActive = true
            WHERE p.status IN :statuses
            AND (CAST(:keyword AS string) IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            AND (:categoryId IS NULL OR c.id = :categoryId)
            GROUP BY p.id, p.name, p.basePrice, c.id, c.name, p.status, p.createdAt
            ORDER BY p.createdAt DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT p.id)
            FROM Product p
            LEFT JOIN p.category c
            WHERE p.status IN :statuses
            AND (CAST(:keyword AS string) IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            AND (:categoryId IS NULL OR c.id = :categoryId)
            """)
    Page<ProductSummaryProjection> findPublicProductsLatest(
            @Param("statuses") List<ProductStatus> statuses,
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            Pageable pageable);

    // =============================================================
    // 공개 상품 목록 — 낮은 가격순 (displayPrice ASC, id ASC)
    // =============================================================

    /**
     * 공개 상품 목록 집계 쿼리 — 낮은 가격순.
     *
     * @param statuses   노출 허용 status 목록
     * @param keyword    상품명 부분 일치 검색어 (null이면 전체)
     * @param categoryId 카테고리 ID 필터 (null이면 전체)
     * @param pageable   페이지 정보
     * @return projection page
     */
    @Query(value = """
            SELECT new com.shop.shop.product.dto.ProductSummaryProjection(
                p.id,
                p.name,
                COALESCE(MIN(v.price), p.basePrice),
                c.id,
                c.name,
                p.status,
                SUM(CASE WHEN v.isActive = true AND v.stock > 0 THEN 1L ELSE 0L END)
            )
            FROM Product p
            LEFT JOIN p.category c
            LEFT JOIN ProductVariant v ON v.product = p AND v.isActive = true
            WHERE p.status IN :statuses
            AND (CAST(:keyword AS string) IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            AND (:categoryId IS NULL OR c.id = :categoryId)
            GROUP BY p.id, p.name, p.basePrice, c.id, c.name, p.status, p.createdAt
            ORDER BY COALESCE(MIN(v.price), p.basePrice) ASC, p.id ASC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT p.id)
            FROM Product p
            LEFT JOIN p.category c
            WHERE p.status IN :statuses
            AND (CAST(:keyword AS string) IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            AND (:categoryId IS NULL OR c.id = :categoryId)
            """)
    Page<ProductSummaryProjection> findPublicProductsPriceAsc(
            @Param("statuses") List<ProductStatus> statuses,
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            Pageable pageable);

    // =============================================================
    // 공개 상품 목록 — 높은 가격순 (displayPrice DESC, id ASC)
    // =============================================================

    /**
     * 공개 상품 목록 집계 쿼리 — 높은 가격순.
     *
     * @param statuses   노출 허용 status 목록
     * @param keyword    상품명 부분 일치 검색어 (null이면 전체)
     * @param categoryId 카테고리 ID 필터 (null이면 전체)
     * @param pageable   페이지 정보
     * @return projection page
     */
    @Query(value = """
            SELECT new com.shop.shop.product.dto.ProductSummaryProjection(
                p.id,
                p.name,
                COALESCE(MIN(v.price), p.basePrice),
                c.id,
                c.name,
                p.status,
                SUM(CASE WHEN v.isActive = true AND v.stock > 0 THEN 1L ELSE 0L END)
            )
            FROM Product p
            LEFT JOIN p.category c
            LEFT JOIN ProductVariant v ON v.product = p AND v.isActive = true
            WHERE p.status IN :statuses
            AND (CAST(:keyword AS string) IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            AND (:categoryId IS NULL OR c.id = :categoryId)
            GROUP BY p.id, p.name, p.basePrice, c.id, c.name, p.status, p.createdAt
            ORDER BY COALESCE(MIN(v.price), p.basePrice) DESC, p.id ASC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT p.id)
            FROM Product p
            LEFT JOIN p.category c
            WHERE p.status IN :statuses
            AND (CAST(:keyword AS string) IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            AND (:categoryId IS NULL OR c.id = :categoryId)
            """)
    Page<ProductSummaryProjection> findPublicProductsPriceDesc(
            @Param("statuses") List<ProductStatus> statuses,
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            Pageable pageable);
}
