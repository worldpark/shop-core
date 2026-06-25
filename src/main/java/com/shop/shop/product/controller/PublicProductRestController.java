package com.shop.shop.product.controller;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
import com.shop.shop.product.service.PublicProductServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 상품 목록/상세 REST 컨트롤러.
 *
 * <p>인증 불필요(permitAll). principal/actorId 미사용(소유권 검사 없음).
 * 비즈니스 로직 없음 — {@link PublicProductServiceResponse} 위임.
 *
 * <p>경로: GET /api/v1/products (목록), GET /api/v1/products/{productId} (상세)
 */
@Tag(name = "product", description = "상품 — 공개 목록·상세 조회")
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class PublicProductRestController {

    private final PublicProductServiceResponse publicProductServiceResponse;

    /**
     * 공개 상품 목록 조회.
     *
     * @param keyword    상품명 부분 일치 검색어 (optional)
     * @param categoryId 카테고리 ID 필터 (optional)
     * @param sort       정렬 기준 (latest/priceAsc/priceDesc, 기본 latest)
     * @param page       페이지 번호 (0-based, 기본 0)
     * @param size       페이지 크기 (기본 20, 최대 100 클램프는 Service 처리)
     * @return 공개 상품 목록 페이지
     */
    @Operation(summary = "공개 상품 목록 조회")
    @GetMapping
    public ResponseEntity<PageResponse<PublicProductSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<PublicProductSummaryResponse> response =
                publicProductServiceResponse.list(keyword, categoryId, sort, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 공개 상품 상세 조회.
     *
     * @param productId 조회할 상품 ID
     * @return 공개 상품 상세 DTO
     */
    @Operation(summary = "공개 상품 상세 조회")
    @GetMapping("/{productId}")
    public ResponseEntity<PublicProductDetailResponse> detail(@PathVariable long productId) {
        PublicProductDetailResponse response = publicProductServiceResponse.detail(productId);
        return ResponseEntity.ok(response);
    }
}
