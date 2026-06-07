package com.shop.shop.product.repository;

import com.shop.shop.product.domain.ProductVariant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
