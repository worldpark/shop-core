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
import com.shop.shop.product.search.ProductSearchHits;
import com.shop.shop.product.search.ProductSearchPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공개 상품 읽기 전용 도메인 서비스.
 *
 * <p>status 화이트리스트(ON_SALE, SOLD_OUT) 적용은 이 Service 단 한 곳에서만 수행한다.
 * Controller·Facade·View는 이 규칙을 재구현하지 않는다.
 *
 * <p>책임:
 * <ul>
 *   <li>findPublicProducts: keyword 존재 시 ES 경로 → PG 재투영 / 폴백 시 기존 pg_trgm 경로</li>
 *   <li>findPrimaryImages: IN 배치 조회 (N+1 회피)</li>
 *   <li>getPublicProductDetail: 상세 단건 집계 (화이트리스트 + 활성 variant 전용)</li>
 *   <li>soldOut/available/displayPrice 판정 헬퍼</li>
 * </ul>
 *
 * <p>레이어: PublicProductRestController → PublicProductServiceResponse → PublicProductService → *Repository
 *           PublicProductFacadeImpl → PublicProductService → *Repository
 *
 * <p><b>ES 읽기 경로 (T5+6)</b>: keyword != null 且 ProductSearchPort 빈 존재 且 쿨다운 비활성 →
 * ES 검색(상품 ID 랭킹+totalHits) → PG 재투영(드리프트 status 제거) → ES 랭킹 순서 보존.
 * ES 비가용/실패/ObjectProvider empty → 기존 findPublicProducts*(pg_trgm) 폴백(계약 동일).
 *
 * <p><b>totalElements 정합 note</b>: ES 경로에서 totalElements=ES totalHits로 둔다.
 * 드리프트로 페이지 내용이 size보다 줄 수 있으나(ES term 필터로 1차 차단 후 PG 재투영 2차),
 * 재카운트는 연관도 페이징 의미를 깨므로 ES totalHits 채택(ADR-011 결정 5(c)).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class PublicProductService {

    /** 공개 노출 허용 status 목록 (변경 금지 — 화이트리스트 단일 소유). */
    static final List<ProductStatus> PUBLIC_STATUSES = List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    /** page size 상한 (클라이언트 임의 과대값 방지). */
    private static final int MAX_SIZE = 100;

    /**
     * ES deep-paging 가드: (page+1)*size 가 이 값 이하여야 ES 경로를 사용한다.
     * ES max_result_window 기본 10000.
     */
    private static final int ES_MAX_RESULT_WINDOW = 10_000;

    /** keyword 최대 허용 길이 — 이를 초과하면 절단 후 ES 전달 (PII 길이 방어). */
    private static final int MAX_KEYWORD_LENGTH = 100;

    private static final String COUNTER_SEARCH_REQUESTS = "product.search.requests";
    private static final String TAG_PATH = "path";
    private static final String PATH_ES = "es";
    private static final String PATH_FALLBACK = "fallback";

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductOptionRepository productOptionRepository;
    private final OptionValueRepository optionValueRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ObjectProvider<ProductSearchPort> searchPortProvider;
    private final MeterRegistry meterRegistry;

    public PublicProductService(
            ProductRepository productRepository,
            ProductImageRepository productImageRepository,
            ProductOptionRepository productOptionRepository,
            OptionValueRepository optionValueRepository,
            ProductVariantRepository productVariantRepository,
            ObjectProvider<ProductSearchPort> searchPortProvider,
            MeterRegistry meterRegistry) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.productOptionRepository = productOptionRepository;
        this.optionValueRepository = optionValueRepository;
        this.productVariantRepository = productVariantRepository;
        this.searchPortProvider = searchPortProvider;
        this.meterRegistry = meterRegistry;
    }

    // =============================================================
    // 목록 조회
    // =============================================================

    /**
     * 공개 상품 목록 집계 쿼리.
     *
     * <p>status 화이트리스트 [ON_SALE, SOLD_OUT] 적용.
     * keyword 존재 시 ES 경로 우선 → ES 비가용/실패 시 pg_trgm 폴백.
     * keyword 없으면 기존 PG 집계 switch 그대로(회귀 0).
     *
     * <p>deep-paging 가드: (page+1)*size > ES_MAX_RESULT_WINDOW이면 ES 스킵 → PG 폴백.
     * keyword 길이 가드: MAX_KEYWORD_LENGTH 초과 시 절단.
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

        // keyword 길이 가드
        if (normalizedKeyword != null && normalizedKeyword.length() > MAX_KEYWORD_LENGTH) {
            normalizedKeyword = normalizedKeyword.substring(0, MAX_KEYWORD_LENGTH);
        }

        // ES 경로 시도 (keyword 존재 시에만)
        if (normalizedKeyword != null) {
            // deep-paging 가드: (page+1)*size > 10000이면 폴백
            if ((long) (page + 1) * clampedSize <= ES_MAX_RESULT_WINDOW) {
                Optional<Page<ProductSummaryProjection>> esResult =
                        tryEsPath(normalizedKeyword, categoryId, sort, page, clampedSize, clamped);
                if (esResult.isPresent()) {
                    incrementCounter(PATH_ES);
                    return esResult.get();
                }
            }
            // ES 비가용 또는 deep-paging → PG 폴백 (pg_trgm)
            incrementCounter(PATH_FALLBACK);
        }

        // keyword 없거나 폴백: 기존 PG 집계 switch
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
     * ES 경로 시도.
     *
     * <p>ObjectProvider empty → empty. 어댑터의 Optional empty → empty. 성공 → PG 재투영 후 반환.
     * 재투영 중 예외는 이 메서드에서 흡수(empty 반환) → 호출자가 PG 폴백.
     */
    private Optional<Page<ProductSummaryProjection>> tryEsPath(
            String keyword, Long categoryId, PublicProductSort sort,
            int page, int clampedSize, Pageable clamped) {
        ProductSearchPort port = searchPortProvider.getIfAvailable();
        if (port == null) {
            return Optional.empty();
        }

        try {
            Optional<ProductSearchHits> hitsOpt = port.search(
                    keyword, categoryId, PUBLIC_STATUSES, sort, page, clampedSize);

            if (hitsOpt.isEmpty()) {
                // ES 비가용 신호 → 폴백
                return Optional.empty();
            }

            ProductSearchHits hits = hitsOpt.get();

            if (hits.ids().isEmpty()) {
                // 검색 결과 없음 — 빈 페이지 반환 (폴백 불필요)
                return Optional.of(new PageImpl<>(List.of(), clamped, hits.totalHits()));
            }

            // PG SoT 재투영 (드리프트 status 이중 필터)
            List<ProductSummaryProjection> projected =
                    productRepository.findPublicProductSummariesByIds(hits.ids(), PUBLIC_STATUSES);

            // ES 랭킹 순서 보존: Map<id, projection> → ES id 순서로 재조립 (드리프트 제거된 id는 드롭)
            Map<Long, ProductSummaryProjection> projMap = projected.stream()
                    .collect(Collectors.toMap(ProductSummaryProjection::productId, Function.identity()));

            List<ProductSummaryProjection> ordered = new ArrayList<>(hits.ids().size());
            for (Long id : hits.ids()) {
                ProductSummaryProjection proj = projMap.get(id);
                if (proj != null) {
                    ordered.add(proj);
                }
                // 드리프트(ES ON_SALE이나 PG HIDDEN/DRAFT): 제거됨 — totalHits는 ES 기준 유지
            }

            return Optional.of(new PageImpl<>(ordered, clamped, hits.totalHits()));

        } catch (Exception e) {
            // 재투영 중 예외(DB 장애 등) — 로그 후 폴백
            log.warn("[ProductSearch] ES path failed during re-projection (reason={}) — falling back to PG",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void incrementCounter(String path) {
        Counter.builder(COUNTER_SEARCH_REQUESTS)
                .tag(TAG_PATH, path)
                .description("Product search request count by path (es|fallback)")
                .register(meterRegistry)
                .increment();
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
