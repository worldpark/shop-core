package com.shop.shop.product.spi;

import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;

import java.util.List;

/**
 * 공개 상품 목록/상세 View 전용 facade (published port).
 *
 * <p>web 모듈의 PublicProductViewController가 product 도메인 내부 Service·Entity·enum을 직접
 * 참조하지 않도록 이 facade를 경유한다. 구현체는 product 내부 {@code service} 패키지에 위치한다.
 *
 * <p>sort는 String으로 받는다 — web이 도메인 enum({@code PublicProductSort})을 컴파일타임에 참조하지 않도록.
 * facade 내부에서 String → PublicProductSort 변환을 담당한다.
 *
 * <p>의존 방향: web → product.spi (단방향). product는 web을 참조하지 않는다.
 */
public interface PublicProductFacade {

    /**
     * 공개 상품 목록 조회.
     *
     * <p>status 화이트리스트 [ON_SALE, SOLD_OUT] 적용.
     * displayPrice·soldOut·primaryImageUrl 조립 포함.
     * sort String → 내부 enum 변환, 정의 외 값은 latest 폴백.
     *
     * @param keyword    상품명 부분 일치 검색어 (null이면 전체)
     * @param categoryId 카테고리 ID 필터 (null이면 전체)
     * @param sort       정렬 문자열 (latest/priceAsc/priceDesc, 기본 latest)
     * @param page       페이지 번호 (0-based)
     * @param size       페이지 크기
     * @return 공개 상품 목록 페이지 (PublicProductPage)
     */
    PublicProductPage listProducts(String keyword, Long categoryId, String sort, int page, int size);

    /**
     * 공개 상품 상세 조회.
     *
     * <p>미존재·DRAFT·HIDDEN → ProductNotFoundException(404).
     * 활성 variant만 포함.
     *
     * @param productId 조회할 상품 ID
     * @return 공개 상품 상세 DTO
     * @throws com.shop.shop.common.exception.ProductNotFoundException 미존재·비공개 → 404
     */
    PublicProductDetailResponse getProductDetail(long productId);

    /**
     * 필터 셀렉트용 카테고리 목록 조회.
     *
     * <p>기존 CategoryService.list()를 재사용한다.
     *
     * @return 카테고리 목록 (flat, sortOrder ASC 정렬)
     */
    List<CategoryResponse> listCategories();

    /**
     * 공개 상품 목록 페이지 DTO (View 전용).
     *
     * <p>web이 Spring Page 객체에 직접 의존하지 않도록 plain record로 제공한다.
     */
    record PublicProductPage(
            List<PublicProductSummaryResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }
}
