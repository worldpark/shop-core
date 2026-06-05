package com.shop.shop.product.repository;

import com.shop.shop.product.domain.ProductVariant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}
