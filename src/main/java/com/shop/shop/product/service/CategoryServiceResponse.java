package com.shop.shop.product.service;

import com.shop.shop.product.dto.CategoryCreateRequest;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.CategoryUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 카테고리 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link CategoryService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다 (Constraint).
 *
 * <p>레이어: *RestController → CategoryServiceResponse → CategoryService → CategoryRepository
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceResponse {

    private final CategoryService categoryService;

    /**
     * 카테고리 목록 조회 — REST 전용.
     * flat List&lt;CategoryResponse&gt; 반환 (parentId 노출).
     *
     * @return 카테고리 목록 DTO
     */
    public List<CategoryResponse> list() {
        return categoryService.list().stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 카테고리 생성 — REST 전용.
     *
     * @param req 생성 요청 DTO
     * @return 생성된 카테고리 응답 DTO
     */
    public CategoryResponse create(CategoryCreateRequest req) {
        return CategoryResponse.from(
                categoryService.createCategory(req.name(), req.slug(), req.parentId(), req.sortOrder())
        );
    }

    /**
     * 카테고리 수정 — REST 전용.
     *
     * @param id  수정할 카테고리 ID
     * @param req 수정 요청 DTO
     * @return 수정된 카테고리 응답 DTO
     */
    public CategoryResponse update(long id, CategoryUpdateRequest req) {
        return CategoryResponse.from(
                categoryService.updateCategory(id, req.name(), req.slug(), req.parentId(), req.sortOrder())
        );
    }
}
