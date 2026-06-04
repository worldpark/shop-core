package com.shop.shop.product.service;

import com.shop.shop.common.exception.CategoryNotFoundException;
import com.shop.shop.common.exception.DuplicateSlugException;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 카테고리 도메인 서비스.
 *
 * <p>카테고리 목록 조회, 생성, 수정 도메인 로직을 단일 소유한다.
 * slug 중복 검증, parent 존재 검증 등 불변식(invariant)을 여기서 담당한다.
 * Controller/ViewController는 이 서비스를 통해서만 데이터를 접근한다 (Repository 직접 호출 금지).
 *
 * <p>레이어: *Controller → CategoryServiceResponse(REST) / ViewController(View) → CategoryService → CategoryRepository
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;

    /**
     * 전체 카테고리 목록 조회.
     * sortOrder ASC, id ASC 정렬.
     *
     * @return 카테고리 목록 (flat, Entity — 호출측에서 DTO 변환)
     */
    @Transactional(readOnly = true)
    public List<Category> list() {
        return categoryRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    /**
     * 카테고리 생성.
     *
     * <p>불변식:
     * <ol>
     *   <li>slug 중복 검증 → {@link DuplicateSlugException}(409)</li>
     *   <li>parentId가 있으면 parent 존재 검증 → {@link CategoryNotFoundException}(404)</li>
     * </ol>
     *
     * @param name      카테고리명
     * @param slug      URL slug (unique)
     * @param parentId  부모 카테고리 ID (null = root)
     * @param sortOrder 정렬 순서
     * @return 저장된 Category Entity
     * @throws DuplicateSlugException     slug 중복
     * @throws CategoryNotFoundException  parent 미존재
     */
    public Category createCategory(String name, String slug, Long parentId, int sortOrder) {
        if (categoryRepository.existsBySlug(slug)) {
            throw new DuplicateSlugException(slug);
        }

        Category parent = resolveParent(parentId);
        Category category = Category.of(name, slug, parent, sortOrder);
        return categoryRepository.save(category);
    }

    /**
     * 카테고리 수정.
     *
     * <p>불변식:
     * <ol>
     *   <li>대상 존재 확인 → {@link CategoryNotFoundException}(404)</li>
     *   <li>slug 변경 시 중복 검증 (자기 자신 제외) → {@link DuplicateSlugException}(409)</li>
     *   <li>parentId가 있으면 parent 존재 검증</li>
     *   <li>자기참조 사이클 방지 (parent==self 금지)</li>
     * </ol>
     *
     * @param categoryId 수정할 카테고리 ID
     * @param name       수정할 이름
     * @param slug       수정할 slug
     * @param parentId   수정할 부모 카테고리 ID (null = root)
     * @param sortOrder  수정할 정렬 순서
     * @return 수정된 Category Entity
     * @throws CategoryNotFoundException  카테고리 미존재 또는 parent 미존재
     * @throws DuplicateSlugException     slug 중복 (자기 제외)
     */
    public Category updateCategory(long categoryId, String name, String slug, Long parentId, int sortOrder) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));

        if (categoryRepository.existsBySlugAndIdNot(slug, categoryId)) {
            throw new DuplicateSlugException(slug);
        }

        Category parent = resolveParent(parentId);

        // 자기참조 사이클 방지 (직접 부모만 차단 — 깊은 사이클 탐지는 범위 밖/YAGNI)
        if (parent != null && parent.getId().equals(categoryId)) {
            throw new CategoryNotFoundException(parentId);
        }

        category.update(name, slug, parent, sortOrder);
        return category;
    }

    /**
     * parentId로 Category 조회 (null이면 null 반환 — root 카테고리).
     *
     * @param parentId 부모 카테고리 ID (null 허용)
     * @return Category 또는 null
     * @throws CategoryNotFoundException parent 미존재
     */
    private Category resolveParent(Long parentId) {
        if (parentId == null) {
            return null;
        }
        return categoryRepository.findById(parentId)
                .orElseThrow(() -> new CategoryNotFoundException(parentId));
    }
}
