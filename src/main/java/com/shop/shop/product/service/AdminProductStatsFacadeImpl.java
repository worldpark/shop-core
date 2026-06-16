package com.shop.shop.product.service;

import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.AdminProductStatsFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * {@link AdminProductStatsFacade} 구현체.
 *
 * <p>product 모듈 내부 비공개 서비스. 관리자 통계 대시보드용 집계만 담당한다.
 * 소유권 검사 없음(전체 통계, admin 전용).
 *
 * <p>게시 상품 기준: ON_SALE + SOLD_OUT (DRAFT / HIDDEN 제외).
 */
@Service
@RequiredArgsConstructor
class AdminProductStatsFacadeImpl implements AdminProductStatsFacade {

    /**
     * 게시 허용 상태 집합.
     */
    private static final Set<ProductStatus> PUBLISHED_STATUSES = Set.of(
            ProductStatus.ON_SALE, ProductStatus.SOLD_OUT
    );

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    /**
     * {@inheritDoc}
     *
     * <p>ProductRepository.countByStatusIn(ON_SALE, SOLD_OUT)에 위임한다.
     */
    @Override
    @Transactional(readOnly = true)
    public long countPublishedProducts() {
        return productRepository.countByStatusIn(PUBLISHED_STATUSES);
    }

    /**
     * {@inheritDoc}
     *
     * <p>soldVariantIds가 비면 DB 조회 없이 0을 즉시 반환한다(빈 컬렉션 가드).
     * ProductVariantRepository.countDistinctPublishedProductsByVariantIdIn에 위임한다.
     */
    @Override
    @Transactional(readOnly = true)
    public long countPublishedProductsWithSales(Collection<Long> soldVariantIds) {
        if (soldVariantIds == null || soldVariantIds.isEmpty()) {
            return 0L;
        }
        return productVariantRepository.countDistinctPublishedProductsByVariantIdIn(
                soldVariantIds, PUBLISHED_STATUSES
        );
    }
}
