package com.shop.shop.product.service;

import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.spi.AdminCategoryFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link AdminCategoryFacade} 구현체.
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link AdminCategoryFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>facade는 얇게 유지한다. 트랜잭션 경계는 {@link CategoryService}에 위임한다.
 * {@code CategoryServiceResponse}는 REST 전용이므로 재사용하지 않고, facade가 직접
 * Entity → {@link CategoryResponse} 매핑을 수행한다.
 */
@Service
@RequiredArgsConstructor
class AdminCategoryFacadeImpl implements AdminCategoryFacade {

    private final CategoryService categoryService;

    /**
     * {@inheritDoc}
     *
     * <p>{@link CategoryService#list()}의 Entity 목록을 {@link CategoryResponse#from}으로 매핑한다.
     */
    @Override
    public List<CategoryResponse> list() {
        return categoryService.list().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link CategoryService#createCategory}에 위임한다.
     */
    @Override
    public void create(String name, String slug, Long parentId, int sortOrder) {
        categoryService.createCategory(name, slug, parentId, sortOrder);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link CategoryService#deleteCategory}에 위임한다.
     */
    @Override
    public void delete(long categoryId) {
        categoryService.deleteCategory(categoryId);
    }
}
