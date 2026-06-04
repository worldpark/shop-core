package com.shop.shop.product.controller;

import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.service.CategoryServiceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 공개 카테고리 조회 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code GET /api/v1/categories → permitAll} (인증 불필요).
 * 비즈니스 로직 없음 — {@link CategoryServiceResponse}에 위임.
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryRestController {

    private final CategoryServiceResponse categoryServiceResponse;

    /**
     * 전체 카테고리 목록 조회 (공개, 인증 불필요).
     * sortOrder ASC, id ASC 정렬. flat 목록 + parentId 노출.
     *
     * @return 200 List&lt;CategoryResponse&gt;
     */
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list() {
        return ResponseEntity.ok(categoryServiceResponse.list());
    }
}
