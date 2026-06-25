package com.shop.shop.product.controller;

import com.shop.shop.product.dto.StockAdjustmentRequest;
import com.shop.shop.product.dto.StockAdjustmentResponse;
import com.shop.shop.product.dto.StockLedgerResponse;
import com.shop.shop.product.service.StockAdjustmentServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SELLER 재고 조정·원장 조회 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/seller/**} → {@code hasRole("SELLER")}.
 * RoleHierarchy(ADMIN > SELLER > CONSUMER)로 ADMIN 함의. CONSUMER → 403, 비인증 → 401.
 * 소유권 검사는 {@link com.shop.shop.product.service.ProductService#getOwnedProduct}에서 수행 (타인 상품 → 404).
 * 비즈니스 로직 없음 — {@link StockAdjustmentServiceResponse}에 위임(forbidden-rule).
 *
 * <p>URL: {@code /api/v1/seller/products/{productId}/variants/{variantId}}
 * <ul>
 *   <li>POST {@code /stock-adjustments} — 재고 조정</li>
 *   <li>GET  {@code /ledger} — 재고 변동 원장 조회</li>
 * </ul>
 */
@Tag(name = "seller-product", description = "판매자 상품 관리 — 등록·수정 (SELLER 이상)")
@RestController
@RequestMapping("/api/v1/seller/products/{productId}/variants/{variantId}")
@RequiredArgsConstructor
public class SellerStockAdjustmentRestController {

    private final StockAdjustmentServiceResponse stockAdjustmentServiceResponse;

    /**
     * 재고 조정 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param variantId 대상 variant ID
     * @param req       조정 요청 DTO (@Valid 검증)
     * @param auth      JWT 인증 객체
     * @return 200 조정 결과 응답 DTO
     */
    @Operation(summary = "재고 조정 (SELLER 이상)")
    @PostMapping("/stock-adjustments")
    public ResponseEntity<StockAdjustmentResponse> adjustStock(
            @PathVariable long productId,
            @PathVariable long variantId,
            @Valid @RequestBody StockAdjustmentRequest req,
            Authentication auth) {
        return ResponseEntity.ok(stockAdjustmentServiceResponse.adjustStock(auth, productId, variantId, req));
    }

    /**
     * 재고 변동 원장 조회 (SELLER 이상, 소유권 검사 포함).
     *
     * <p>페이지네이션은 Pageable로 처리. 기본 정렬은 inventory에서 occurred_at DESC 고정.
     *
     * @param productId 대상 상품 ID
     * @param variantId 대상 variant ID
     * @param pageable  페이지 정보 (page, size)
     * @param auth      JWT 인증 객체
     * @return 200 원장 응답 DTO Page
     */
    @GetMapping("/ledger")
    public ResponseEntity<Page<StockLedgerResponse>> getLedger(
            @PathVariable long productId,
            @PathVariable long variantId,
            Pageable pageable,
            Authentication auth) {
        return ResponseEntity.ok(stockAdjustmentServiceResponse.getLedger(auth, productId, variantId, pageable));
    }
}
