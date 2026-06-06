package com.shop.shop.product.service;

import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 공개 상품 읽기 전용 도메인 서비스.
 *
 * <p>status 화이트리스트(ON_SALE, SOLD_OUT) 적용은 이 Service 단 한 곳에서만 수행한다.
 * Controller·Facade·View는 이 규칙을 재구현하지 않는다.
 *
 * <p>책임:
 * <ul>
 *   <li>findPublicProducts: 목록 페이지 집계 쿼리 + 정렬별 메서드 선택</li>
 *   <li>findPrimaryImages: IN 배치 조회 (N+1 회피)</li>
 *   <li>getPublicProductDetail: 상세 단건 집계 (화이트리스트 + 활성 variant 전용)</li>
 *   <li>soldOut/available/displayPrice 판정 헬퍼</li>
 * </ul>
 *
 * <p>레이어: PublicProductRestController → PublicProductServiceResponse → PublicProductService → *Repository
 *           PublicProductFacadeImpl → PublicProductService → *Repository
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicProductService {

    /** 공개 노출 허용 status 목록 (변경 금지 — 화이트리스트 단일 소유). */
    static final List<ProductStatus> PUBLIC_STATUSES = List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    /** page size 상한 (클라이언트 임의 과대값 방지). */
    private static final int MAX_SIZE = 100;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductOptionRepository productOptionRepository;
    private final OptionValueRepository optionValueRepository;
    private final ProductVariantRepository productVariantRepository;

    // =============================================================
    // 목록 조회
    // =============================================================

    /**
     * 공개 상품 목록 집계 쿼리.
     *
     * <p>status 화이트리스트 [ON_SALE, SOLD_OUT] 적용.
     * sort별 repository 메서드 선택(JPQL 정적 ORDER BY — 클라이언트 임의 정렬 차단).
     * displayPrice = COALESCE(MIN(활성 v.price), p.basePrice) — DB 단계 계산, 메모리 정렬 금지.
     * size는 1~MAX_SIZE 범위로 클램프.
     *
     * @param keyword    상품명 부분 일치 검색어 (null이면 전체)
     * @param categoryId 카테고리 ID 필터 (null이면 전체)
     * @param sort       정렬 기준
     * @param pageable   페이지 정보 (size가 MAX_SIZE를 초과하면 MAX_SIZE로 클램프)
     * @return projection page
     */
    public Page<ProductSummaryProjection> findPublicProducts(
            String keyword, Long categoryId, PublicProductSort sort, Pageable pageable) {

        int clampedSize = Math.min(Math.max(pageable.getPageSize(), 1), MAX_SIZE);
        int page = Math.max(pageable.getPageNumber(), 0);
        Pageable clamped = PageRequest.of(page, clampedSize);

        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        return switch (sort) {
            case PRICE_ASC -> productRepository.findPublicProductsPriceAsc(
                    PUBLIC_STATUSES, normalizedKeyword, categoryId, clamped);
            case PRICE_DESC -> productRepository.findPublicProductsPriceDesc(
                    PUBLIC_STATUSES, normalizedKeyword, categoryId, clamped);
            default -> productRepository.findPublicProductsLatest(
                    PUBLIC_STATUSES, normalizedKeyword, categoryId, clamped);
        };
    }

    /**
     * 현재 페이지 상품들의 대표 이미지를 IN 배치 조회한다.
     *
     * <p>N+1 회피. 빈 리스트이면 쿼리 생략.
     *
     * @param productIds 조회할 상품 ID 목록
     * @return productId → ProductImage 맵 (대표 이미지가 없는 상품은 맵에 없음)
     */
    public Map<Long, ProductImage> findPrimaryImages(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        return productImageRepository.findByProductIdInAndIsPrimaryTrue(productIds)
                .stream()
                .collect(Collectors.toMap(
                        image -> image.getProduct().getId(),
                        image -> image
                ));
    }

    // =============================================================
    // 상세 조회
    // =============================================================

    /**
     * 공개 상품 상세 단건 조회.
     *
     * <p>상품이 없거나 status가 DRAFT/HIDDEN이면 ProductNotFoundException(404).
     * 활성 variant만 로드(findByProductIdAndIsActiveTrue). 비활성 variant는 절대 노출 금지.
     *
     * @param productId 조회할 상품 ID
     * @return DetailAggregate (상품 + 이미지 + 옵션/옵션값 + 활성 variant)
     * @throws ProductNotFoundException 미존재·DRAFT·HIDDEN → 404
     */
    public DetailAggregate getPublicProductDetail(long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (!PUBLIC_STATUSES.contains(product.getStatus())) {
            throw new ProductNotFoundException(productId);
        }

        List<ProductImage> images = productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
        List<ProductOption> options = productOptionRepository.findByProductIdOrderById(productId);
        List<OptionValue> optionValues = optionValueRepository.findByOption_ProductIdOrderByIdAsc(productId);
        List<ProductVariant> activeVariants = productVariantRepository.findByProductIdAndIsActiveTrue(productId);

        return new DetailAggregate(product, images, options, optionValues, activeVariants);
    }

    // =============================================================
    // 판정 헬퍼
    // =============================================================

    /**
     * 상품 soldOut 판정.
     *
     * <p>soldOut = !(status==ON_SALE && 구매가능 활성 variant 1개 이상 존재)
     * - SOLD_OUT 상품: 무조건 soldOut=true
     * - ON_SALE + 구매가능 variant 없음: soldOut=true
     * - ON_SALE + 구매가능 variant 있음: soldOut=false
     *
     * @param status              상품 status
     * @param hasPurchasableVariant 구매가능(재고>0) 활성 variant 존재 여부
     * @return soldOut 여부
     */
    public boolean isSoldOut(ProductStatus status, boolean hasPurchasableVariant) {
        return !(status == ProductStatus.ON_SALE && hasPurchasableVariant);
    }

    /**
     * 상품 displayPrice 계산 (목록 projection 기반).
     *
     * <p>projection의 displayPrice를 그대로 반환한다.
     * projection 쿼리에서 이미 COALESCE(MIN(v.price), p.basePrice)로 계산했다.
     *
     * @param projection 목록 집계 projection
     * @return displayPrice
     */
    public BigDecimal resolveDisplayPrice(ProductSummaryProjection projection) {
        return projection.displayPrice();
    }

    /**
     * 상세 조회 시 displayPrice 계산 (활성 variant min(price) 또는 basePrice 폴백).
     *
     * <p>상세는 단건이므로 메모리 계산 허용(목록과 달리 N+1 없음).
     *
     * @param product       상품 Entity
     * @param activeVariants 활성 variant 목록
     * @return displayPrice
     */
    public BigDecimal resolveDetailDisplayPrice(Product product, List<ProductVariant> activeVariants) {
        return activeVariants.stream()
                .map(ProductVariant::getPrice)
                .min(BigDecimal::compareTo)
                .orElse(product.getBasePrice());
    }

    /**
     * variant available 판정.
     *
     * <p>available = (product.status==ON_SALE && variant.stock>0)
     * SOLD_OUT 상품의 variant는 재고가 있어도 available=false.
     *
     * @param productStatus 상품 status
     * @param variantStock  variant 재고
     * @return available 여부
     */
    public boolean isVariantAvailable(ProductStatus productStatus, int variantStock) {
        return productStatus == ProductStatus.ON_SALE && variantStock > 0;
    }

    // =============================================================
    // 내부 집계 결과 record
    // =============================================================

    /**
     * 상세 조회 집계 결과 (Entity 묶음 — product 모듈 내부 전용).
     *
     * <p>facade/ServiceResponse가 이 record의 Entity들을 DTO로 변환한다.
     * product 모듈 밖으로 노출하지 않는다.
     */
    public record DetailAggregate(
            Product product,
            List<ProductImage> images,
            List<ProductOption> options,
            List<OptionValue> optionValues,
            List<ProductVariant> activeVariants
    ) {
    }
}
