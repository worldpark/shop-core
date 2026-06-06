package com.shop.shop.product.service;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
import com.shop.shop.product.domain.ProductImage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * REST 공개 상품 조회 응답 조합 서비스 (REST 전용).
 *
 * <p>비즈니스 로직 없음 — PublicProductService 위임 + PublicProductDtoMapper를 통한 DTO 변환.
 *
 * <p>레이어: PublicProductRestController → PublicProductServiceResponse → PublicProductService
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicProductServiceResponse {

    private final PublicProductService publicProductService;
    private final PublicProductDtoMapper dtoMapper;

    /**
     * 공개 상품 목록 조회.
     *
     * <p>처리 순서:
     * 1. sort String → PublicProductSort 변환.
     * 2. PublicProductService.findPublicProducts로 집계 page 조회.
     * 3. 현재 페이지 productId 목록으로 대표 이미지 IN 배치 조회 (N+1 회피).
     * 4. projection + 대표이미지 → PublicProductSummaryResponse 조립.
     * 5. PageResponse.of 래핑.
     *
     * @param keyword    검색어 (null이면 전체)
     * @param categoryId 카테고리 ID (null이면 전체)
     * @param sort       정렬 문자열 (latest/priceAsc/priceDesc, 기본 latest)
     * @param page       페이지 번호 (0-based)
     * @param size       페이지 크기
     * @return PageResponse<PublicProductSummaryResponse>
     */
    public PageResponse<PublicProductSummaryResponse> list(
            String keyword, Long categoryId, String sort, int page, int size) {

        PublicProductSort sortEnum = PublicProductSort.from(sort);
        Page<ProductSummaryProjection> projectionPage = publicProductService.findPublicProducts(
                keyword, categoryId, sortEnum, PageRequest.of(page, size));

        List<Long> productIds = projectionPage.getContent().stream()
                .map(ProductSummaryProjection::productId)
                .toList();

        Map<Long, ProductImage> primaryImageMap = publicProductService.findPrimaryImages(productIds);

        List<PublicProductSummaryResponse> content = projectionPage.getContent().stream()
                .map(projection -> dtoMapper.toSummaryResponse(projection, primaryImageMap))
                .toList();

        Page<PublicProductSummaryResponse> responsePage = new PageImpl<>(
                content, projectionPage.getPageable(), projectionPage.getTotalElements());

        return PageResponse.of(responsePage);
    }

    /**
     * 공개 상품 상세 조회.
     *
     * @param productId 상품 ID
     * @return PublicProductDetailResponse
     * @throws com.shop.shop.common.exception.ProductNotFoundException 미존재·DRAFT·HIDDEN → 404
     */
    public PublicProductDetailResponse detail(long productId) {
        PublicProductService.DetailAggregate aggregate = publicProductService.getPublicProductDetail(productId);
        return dtoMapper.toDetailResponse(aggregate);
    }
}
