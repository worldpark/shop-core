package com.shop.shop.product.controller;

import com.shop.shop.product.dto.ProductVariantCreateRequest;
import com.shop.shop.product.dto.ProductVariantResponse;
import com.shop.shop.product.dto.ProductVariantUpdateRequest;
import com.shop.shop.product.service.ProductVariantServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SELLER 상품 variant 관리 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/seller/**} → {@code hasRole("SELLER")}.
 * RoleHierarchy(ADMIN > SELLER > CONSUMER)로 ADMIN 함의. CONSUMER → 403, 비인증 → 401.
 * 소유권 검사는 ProductService에서 수행 (타인 상품 → 404).
 * 비즈니스 로직 없음 — {@link ProductVariantServiceResponse}에 위임.
 */
@RestController
@RequestMapping("/api/v1/seller/products/{productId}/variants")
@RequiredArgsConstructor
public class SellerProductVariantRestController {

    private final ProductVariantServiceResponse productVariantServiceResponse;

    /**
     * variant 목록 조회 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param auth      JWT 인증 객체
     * @return 200 variant 목록 응답 DTO
     */
    @GetMapping
    public ResponseEntity<List<ProductVariantResponse>> listVariants(
            @PathVariable long productId,
            Authentication auth) {
        return ResponseEntity.ok(productVariantServiceResponse.listVariants(auth, productId));
    }

    /**
     * variant 생성 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param req       variant 생성 요청 DTO (@Valid 검증)
     * @param auth      JWT 인증 객체
     * @return 200 생성된 variant 응답 DTO
     */
    @PostMapping
    public ResponseEntity<ProductVariantResponse> createVariant(
            @PathVariable long productId,
            @Valid @RequestBody ProductVariantCreateRequest req,
            Authentication auth) {
        return ResponseEntity.ok(productVariantServiceResponse.createVariant(auth, productId, req));
    }

    /**
     * variant 수정 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param variantId 수정할 variant ID
     * @param req       variant 수정 요청 DTO (@Valid 검증)
     * @param auth      JWT 인증 객체
     * @return 200 수정된 variant 응답 DTO
     */
    @PatchMapping("/{variantId}")
    public ResponseEntity<ProductVariantResponse> updateVariant(
            @PathVariable long productId,
            @PathVariable long variantId,
            @Valid @RequestBody ProductVariantUpdateRequest req,
            Authentication auth) {
        return ResponseEntity.ok(productVariantServiceResponse.updateVariant(auth, productId, variantId, req));
    }
}
