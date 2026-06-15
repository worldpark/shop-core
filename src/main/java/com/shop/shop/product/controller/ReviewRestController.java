package com.shop.shop.product.controller;

import com.shop.shop.product.dto.ProductReviewSummaryResponse;
import com.shop.shop.product.dto.ReviewCreateRequest;
import com.shop.shop.product.dto.ReviewResponse;
import com.shop.shop.product.dto.ReviewUpdateRequest;
import com.shop.shop.product.service.ReviewServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리뷰 REST 컨트롤러.
 *
 * <p>인가:
 * <ul>
 *   <li>POST /api/v1/reviews — hasRole("CONSUMER")</li>
 *   <li>PATCH /api/v1/reviews/{id} — hasRole("CONSUMER")</li>
 *   <li>DELETE /api/v1/reviews/{id} — hasRole("CONSUMER")</li>
 *   <li>GET /api/v1/products/{productId}/reviews — permitAll(공개)</li>
 * </ul>
 *
 * <p>비즈니스 로직 없음 — ReviewServiceResponse 위임. principal은 Authentication.
 */
@RestController
@RequiredArgsConstructor
public class ReviewRestController {

    private final ReviewServiceResponse reviewServiceResponse;

    /**
     * 리뷰 작성.
     * POST /api/v1/reviews → 201
     */
    @PostMapping("/api/v1/reviews")
    public ResponseEntity<ReviewResponse> create(
            Authentication auth,
            @Valid @RequestBody ReviewCreateRequest req) {

        ReviewResponse response = reviewServiceResponse.create(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 리뷰 수정.
     * PATCH /api/v1/reviews/{id} → 200
     */
    @PatchMapping("/api/v1/reviews/{id}")
    public ResponseEntity<ReviewResponse> update(
            Authentication auth,
            @PathVariable("id") long reviewId,
            @Valid @RequestBody ReviewUpdateRequest req) {

        ReviewResponse response = reviewServiceResponse.update(auth, reviewId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * 리뷰 삭제.
     * DELETE /api/v1/reviews/{id} → 204
     */
    @DeleteMapping("/api/v1/reviews/{id}")
    public ResponseEntity<Void> delete(
            Authentication auth,
            @PathVariable("id") long reviewId) {

        reviewServiceResponse.delete(auth, reviewId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 상품 리뷰 목록 + 집계 조회 (공개).
     * GET /api/v1/products/{productId}/reviews → 200
     */
    @GetMapping("/api/v1/products/{productId}/reviews")
    public ResponseEntity<ProductReviewSummaryResponse> getProductReviews(
            @PathVariable long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        ProductReviewSummaryResponse response = reviewServiceResponse.getProductReviews(productId, page, size);
        return ResponseEntity.ok(response);
    }
}
