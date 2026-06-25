package com.shop.shop.product.controller;

import com.shop.shop.product.dto.ProductCreateRequest;
import com.shop.shop.product.dto.ProductResponse;
import com.shop.shop.product.dto.ProductUpdateRequest;
import com.shop.shop.product.service.ProductServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SELLER 상품 등록/수정 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/seller/**} → {@code hasRole("SELLER")}.
 * RoleHierarchy(ADMIN > SELLER > CONSUMER)로 ADMIN 함의. CONSUMER → 403, 비인증 → 401.
 * 비즈니스 로직 없음 — {@link ProductServiceResponse}에 위임.
 */
@Tag(name = "seller-product", description = "판매자 상품 관리 — 등록·수정 (SELLER 이상)")
@RestController
@RequestMapping("/api/v1/seller/products")
@RequiredArgsConstructor
public class SellerProductRestController {

    private final ProductServiceResponse productServiceResponse;

    /**
     * 상품 등록 (SELLER 이상).
     * status는 요청에서 미수신 — ProductService가 DRAFT 강제.
     *
     * @param req  등록 요청 DTO (@Valid 검증)
     * @param auth JWT 인증 객체 (principal=userId long)
     * @return 200 ProductResponse (status=DRAFT)
     */
    @Operation(summary = "상품 등록 (SELLER 이상)")
    @PostMapping
    public ResponseEntity<ProductResponse> register(
            @Valid @RequestBody ProductCreateRequest req,
            Authentication auth) {
        return ResponseEntity.ok(productServiceResponse.register(auth, req));
    }

    /**
     * 상품 수정 (SELLER 이상, 소유권 검사 포함).
     * 소유권 검사는 ProductService에서 수행 (타인 상품 → 404).
     *
     * @param productId 수정할 상품 ID
     * @param req       수정 요청 DTO (@Valid 검증)
     * @param auth      JWT 인증 객체 (principal=userId long)
     * @return 200 ProductResponse
     */
    @PatchMapping("/{productId}")
    public ResponseEntity<ProductResponse> update(
            @PathVariable long productId,
            @Valid @RequestBody ProductUpdateRequest req,
            Authentication auth) {
        return ResponseEntity.ok(productServiceResponse.update(auth, productId, req));
    }
}
