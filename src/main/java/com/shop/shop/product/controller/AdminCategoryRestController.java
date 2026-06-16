package com.shop.shop.product.controller;

import com.shop.shop.product.dto.CategoryCreateRequest;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.CategoryUpdateRequest;
import com.shop.shop.product.service.CategoryServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN 카테고리 관리 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} (008 기존 매처).
 * 비ADMIN → 403, 비인증 → 401.
 * 비즈니스 로직 없음 — {@link CategoryServiceResponse}에 위임.
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryRestController {

    private final CategoryServiceResponse categoryServiceResponse;

    /**
     * 카테고리 생성 (ADMIN 전용).
     *
     * @param req 생성 요청 DTO (@Valid 검증)
     * @return 200 CategoryResponse
     */
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest req) {
        return ResponseEntity.ok(categoryServiceResponse.create(req));
    }

    /**
     * 카테고리 수정 (ADMIN 전용).
     *
     * @param categoryId 수정할 카테고리 ID
     * @param req        수정 요청 DTO (@Valid 검증)
     * @return 200 CategoryResponse
     */
    @PatchMapping("/{categoryId}")
    public ResponseEntity<CategoryResponse> update(
            @PathVariable long categoryId,
            @Valid @RequestBody CategoryUpdateRequest req) {
        return ResponseEntity.ok(categoryServiceResponse.update(categoryId, req));
    }

    /**
     * 카테고리 삭제 (ADMIN 전용).
     *
     * <p>삭제 시 상품의 category_id는 NULL(미분류 전환), 자식 카테고리의 parent_id는 NULL(root 승격).
     * DB FK ON DELETE SET NULL에 위임한다.
     *
     * @param categoryId 삭제할 카테고리 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> delete(@PathVariable long categoryId) {
        categoryServiceResponse.delete(categoryId);
        return ResponseEntity.noContent().build();
    }
}
