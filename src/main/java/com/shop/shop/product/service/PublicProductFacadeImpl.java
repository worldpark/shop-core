package com.shop.shop.product.service;

import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
import com.shop.shop.product.spi.PublicProductFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * {@link PublicProductFacade} 구현체 (package-private).
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link PublicProductFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>sort String → PublicProductSort 변환</li>
 *   <li>PublicProductService 위임</li>
 *   <li>PublicProductDtoMapper를 통한 Entity → View DTO 변환</li>
 *   <li>CategoryService 위임 (필터 목록)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PublicProductFacadeImpl implements PublicProductFacade {

    private final PublicProductService publicProductService;
    private final CategoryService categoryService;
    private final PublicProductDtoMapper dtoMapper;

    /**
     * {@inheritDoc}
     *
     * <p>sort String → PublicProductSort 변환 후 PublicProductService에 위임한다.
     * 대표 이미지는 IN 배치 조회로 N+1 회피.
     */
    @Override
    public PublicProductPage listProducts(String keyword, Long categoryId, String sort, int page, int size) {
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

        return new PublicProductPage(
                content,
                projectionPage.getNumber(),
                projectionPage.getSize(),
                projectionPage.getTotalElements(),
                projectionPage.getTotalPages()
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>PublicProductService.getPublicProductDetail에 위임 후 Entity → DTO 변환한다.
     */
    @Override
    public PublicProductDetailResponse getProductDetail(long productId) {
        PublicProductService.DetailAggregate aggregate = publicProductService.getPublicProductDetail(productId);
        return dtoMapper.toDetailResponse(aggregate);
    }

    /**
     * {@inheritDoc}
     *
     * <p>CategoryService.list()를 호출하고 CategoryResponse로 변환한다.
     */
    @Override
    public List<CategoryResponse> listCategories() {
        return categoryService.list().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
