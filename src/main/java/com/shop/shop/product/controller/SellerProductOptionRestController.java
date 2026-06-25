package com.shop.shop.product.controller;

import com.shop.shop.product.dto.OptionValueCreateRequest;
import com.shop.shop.product.dto.OptionValueResponse;
import com.shop.shop.product.dto.ProductOptionCreateRequest;
import com.shop.shop.product.dto.ProductOptionResponse;
import com.shop.shop.product.service.ProductOptionServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SELLER 상품 옵션 관리 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/seller/**} → {@code hasRole("SELLER")}.
 * RoleHierarchy(ADMIN > SELLER > CONSUMER)로 ADMIN 함의. CONSUMER → 403, 비인증 → 401.
 * 소유권 검사는 ProductService에서 수행 (타인 상품 → 404).
 * 비즈니스 로직 없음 — {@link ProductOptionServiceResponse}에 위임.
 */
@Tag(name = "seller-product", description = "판매자 상품 관리 — 등록·수정 (SELLER 이상)")
@RestController
@RequestMapping("/api/v1/seller/products/{productId}/options")
@RequiredArgsConstructor
public class SellerProductOptionRestController {

    private final ProductOptionServiceResponse productOptionServiceResponse;

    /**
     * 옵션 목록 조회 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param auth      JWT 인증 객체
     * @return 200 옵션 목록 (옵션값 포함)
     */
    @Operation(summary = "상품 옵션 목록 조회 (SELLER 이상)")
    @GetMapping
    public ResponseEntity<List<ProductOptionResponse>> listOptions(
            @PathVariable long productId,
            Authentication auth) {
        return ResponseEntity.ok(productOptionServiceResponse.listOptions(auth, productId));
    }

    /**
     * 옵션 생성 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param req       옵션 생성 요청 DTO (@Valid 검증)
     * @param auth      JWT 인증 객체
     * @return 200 생성된 옵션 응답 DTO
     */
    @PostMapping
    public ResponseEntity<ProductOptionResponse> createOption(
            @PathVariable long productId,
            @Valid @RequestBody ProductOptionCreateRequest req,
            Authentication auth) {
        return ResponseEntity.ok(productOptionServiceResponse.createOption(auth, productId, req));
    }

    /**
     * 옵션값 생성 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param optionId  대상 옵션 ID
     * @param req       옵션값 생성 요청 DTO (@Valid 검증)
     * @param auth      JWT 인증 객체
     * @return 200 생성된 옵션값 응답 DTO
     */
    @PostMapping("/{optionId}/values")
    public ResponseEntity<OptionValueResponse> createOptionValue(
            @PathVariable long productId,
            @PathVariable long optionId,
            @Valid @RequestBody OptionValueCreateRequest req,
            Authentication auth) {
        return ResponseEntity.ok(productOptionServiceResponse.createOptionValue(auth, productId, optionId, req));
    }

    /**
     * 옵션 삭제 (SELLER 이상, 소유권 검사 포함).
     *
     * <p>DB ON DELETE CASCADE로 option_values·variant_values 자동 정리.
     * 인가: /api/v1/seller/** → hasRole("SELLER"), ADMIN 함의.
     * 소유권: ProductService.getOwnedProduct — 타인 상품 → 404.
     *
     * @param productId 대상 상품 ID
     * @param optionId  삭제할 옵션 ID
     * @param auth      JWT 인증 객체
     * @return 204 No Content
     */
    @DeleteMapping("/{optionId}")
    public ResponseEntity<Void> deleteOption(
            @PathVariable long productId,
            @PathVariable long optionId,
            Authentication auth) {
        productOptionServiceResponse.deleteOption(auth, productId, optionId);
        return ResponseEntity.noContent().build();
    }
}
