package com.shop.shop.product.repository;

import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductStockSum;
import com.shop.shop.product.dto.VariantProductMapping;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ProductVariant JPA Repository.
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    /**
     * SKU 전역 중복 여부 확인 (V3 불변식 — 생성 시).
     */
    boolean existsBySku(String sku);

    /**
     * SKU 전역 중복 여부 확인 — 자기 자신 제외 (V3 불변식 — 수정 시).
     */
    boolean existsBySkuAndIdNot(String sku, long id);

    /**
     * 상품의 variant 목록 조회 (V9 조합 중복 검증 및 목록 조회).
     *
     * <p>@EntityGraph: ProductVariant.optionValues 컬렉션이 @ManyToMany(FetchType.LAZY)이므로,
     * open-in-view=false 환경에서 트랜잭션 종료 후 ProductVariantResponse.from() 변환 시
     * LazyInitializationException이 발생한다. EntityGraph로 즉시 로딩하여 해소한다.
     * (list/view 경로 — GET /api/v1/seller/products/{id}/variants, GET /seller/products/{id}/variants)
     * create/update 응답 경로는 ProductVariant.create()가 new HashSet<>()으로 메모리에 채우므로 안전.
     * 실 DB 검증: docker-compose 수동 확인 (테스트 프로파일이 DataSource/JPA 자동설정을 제외하므로
     * @DataJpaTest 슬라이스 테스트는 이 프로젝트에서 동작하지 않음).
     */
    @EntityGraph(attributePaths = "optionValues")
    List<ProductVariant> findByProductId(long productId);

    /**
     * 상품의 활성 variant 목록 조회 (공개 상세 전용).
     *
     * <p>비활성(isActive=false) variant를 절대 공개 상세에 노출하지 않는다.
     * @EntityGraph로 optionValues를 즉시 로딩해 LazyInitializationException을 방지한다.
     * open-in-view=false 환경에서 트랜잭션 종료 후 DTO 변환 시 LazyInit 오류를 막는다.
     * 실 DB 검증: docker-compose 수동 확인 항목.
     *
     * @param productId 상품 ID
     * @return 활성 variant 목록 (isActive=true인 것만, optionValues 즉시 로딩)
     */
    @EntityGraph(attributePaths = "optionValues")
    List<ProductVariant> findByProductIdAndIsActiveTrue(long productId);

    /**
     * 단건 variant + product 즉시 로딩 조회 (장바구니 담기·수량변경 검증용).
     *
     * <p>product LAZY 회피 — productName/status 접근 시 LazyInitializationException 방지.
     * optionValues도 즉시 로딩하여 optionLabel 조립에 사용한다.
     *
     * @param id variant ID
     * @return variant (product·optionValues 즉시 로딩)
     */
    @EntityGraph(attributePaths = {"product", "optionValues"})
    Optional<ProductVariant> findWithProductById(long id);

    /**
     * IN 배치 variant + product + optionValues 즉시 로딩 조회 (장바구니 조회 합성용, N+1 회피).
     *
     * <p>존재하는 id만 반환한다. 목록에 없는 id(삭제됨)는 cart가 available=false 폴백으로 처리한다.
     *
     * @param ids 조회할 variant ID 컬렉션
     * @return variant 목록 (product·optionValues 즉시 로딩)
     */
    @EntityGraph(attributePaths = {"product", "optionValues"})
    List<ProductVariant> findByIdIn(Collection<Long> ids);

    /**
     * IN 배치 variant + product + optionValues + option 즉시 로딩 조회 (주문 스냅샷 생성용, N+1 회피).
     *
     * <p>존재하는 id만 반환한다. optionValues.option(옵션명)까지 즉시 로딩해
     * 주문 시점 스냅샷(optionName/optionValue) 조립 시 LazyInitializationException을 방지한다.
     *
     * @param ids 조회할 variant ID 컬렉션
     * @return variant 목록 (product·optionValues·option 즉시 로딩)
     */
    @EntityGraph(attributePaths = {"product", "optionValues", "optionValues.option"})
    List<ProductVariant> findWithOptionsByIdIn(Collection<Long> ids);

    /**
     * 상품 ID 집합 기준 상품별 재고 합계 집계 쿼리.
     *
     * <p>variant가 없는 상품은 결과에 포함되지 않는다(totalStock=0 항목 미포함 — 합산 시 0 처리).
     * COALESCE로 NULL → 0 방어.
     *
     * @param productIds 집계 대상 상품 ID 컬렉션
     * @return 상품별 재고 합계 projection 리스트
     */
    @Query("""
            SELECT new com.shop.shop.product.dto.ProductStockSum(
                pv.product.id,
                COALESCE(SUM(pv.stock), 0)
            )
            FROM ProductVariant pv
            WHERE pv.product.id IN :productIds
            GROUP BY pv.product.id
            """)
    List<ProductStockSum> findStockSumsByProductIdIn(@Param("productIds") Collection<Long> productIds);

    /**
     * 상품 ID 집합 기준 variantId ↔ productId 매핑 조회.
     *
     * <p>web 계층에서 variant별 판매 집계를 상품 기준으로 병합할 때 사용한다.
     * 삭제된 variant는 조회되지 않는다.
     *
     * @param productIds 조회 대상 상품 ID 컬렉션
     * @return variantId → productId 매핑 projection 리스트
     */
    @Query("""
            SELECT new com.shop.shop.product.dto.VariantProductMapping(
                pv.id,
                pv.product.id
            )
            FROM ProductVariant pv
            WHERE pv.product.id IN :productIds
            """)
    List<VariantProductMapping> findVariantProductMappingsByProductIdIn(@Param("productIds") Collection<Long> productIds);

    /**
     * 판매자 소유 전체 상품의 variantId ↔ productId 매핑 조회 (SSE 전체 스코프 전용).
     *
     * <p>페이지 제한 없이 소유자(ownerId)의 모든 상품에 속한 variant를 1쿼리로 조회한다.
     * SSE 연결 시점에 전체 소유 variantId 세트를 캐시하기 위해 사용한다(N+1 없음).
     * variant가 없는 상품은 결과에 포함되지 않는다.
     *
     * @param ownerId 소유자(판매자) 사용자 ID
     * @return variantId → productId 매핑 projection 리스트
     */
    @Query("""
            SELECT new com.shop.shop.product.dto.VariantProductMapping(
                pv.id,
                pv.product.id
            )
            FROM ProductVariant pv
            WHERE pv.product.ownerId = :ownerId
            """)
    List<VariantProductMapping> findVariantProductMappingsByOwnerId(@Param("ownerId") long ownerId);

    /**
     * 주어진 variantId 집합 중 게시 상태({@code publishedStatuses}) 상품에 속한 distinct 상품 수.
     * 관리자 통계 대시보드 — 상품 판매율 분자(최근 30일 판매된 게시 상품 distinct 수) 계산에 사용.
     *
     * <p>variantIds가 비면 facade에서 가드해 이 메서드를 호출하지 않는다.
     * 미게시 상품(DRAFT/HIDDEN)의 variant는 {@code publishedStatuses} 조건으로 자동 제외된다.
     *
     * @param variantIds        판매된 variant ID 컬렉션
     * @param publishedStatuses 게시 허용 상태 컬렉션 (ON_SALE, SOLD_OUT)
     * @return 조건에 맞는 DISTINCT 상품 수
     */
    @Query("""
            SELECT COUNT(DISTINCT pv.product.id)
            FROM ProductVariant pv
            WHERE pv.id IN :variantIds
              AND pv.product.status IN :publishedStatuses
            """)
    long countDistinctPublishedProductsByVariantIdIn(
            @Param("variantIds") Collection<Long> variantIds,
            @Param("publishedStatuses") Collection<ProductStatus> publishedStatuses
    );
}
