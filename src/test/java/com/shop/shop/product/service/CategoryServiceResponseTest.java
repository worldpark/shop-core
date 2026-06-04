package com.shop.shop.product.service;

import com.shop.shop.product.domain.Category;
import com.shop.shop.product.dto.CategoryCreateRequest;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.CategoryUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CategoryServiceResponse 단위 테스트.
 * CategoryService 위임 + DTO 변환 + Entity 미노출 단언.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceResponseTest {

    @Mock
    private CategoryService categoryService;

    @InjectMocks
    private CategoryServiceResponse categoryServiceResponse;

    @Test
    @DisplayName("list — List<Category> → List<CategoryResponse> 변환, Entity 미노출")
    void list_converts_to_dto_list() {
        Category cat = categoryWithId(1L, "전자", "electronics");
        when(categoryService.list()).thenReturn(List.of(cat));

        List<CategoryResponse> result = categoryServiceResponse.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(1L);
        assertThat(result.get(0).name()).isEqualTo("전자");
        assertThat(result.get(0).slug()).isEqualTo("electronics");
        assertThat(result.get(0).parentId()).isNull();
        // Entity 미노출 단언: 반환 타입이 CategoryResponse(record)
        assertThat(result.get(0)).isInstanceOf(CategoryResponse.class);
    }

    @Test
    @DisplayName("create — CategoryCreateRequest → CategoryService.createCategory 위임 + CategoryResponse 반환")
    void create_delegates_to_service_and_returns_dto() {
        Category cat = categoryWithId(2L, "모바일", "mobile");
        when(categoryService.createCategory("모바일", "mobile", null, 0)).thenReturn(cat);

        CategoryCreateRequest req = new CategoryCreateRequest("모바일", "mobile", null, 0);
        CategoryResponse result = categoryServiceResponse.create(req);

        assertThat(result.categoryId()).isEqualTo(2L);
        assertThat(result.slug()).isEqualTo("mobile");
    }

    @Test
    @DisplayName("update — CategoryUpdateRequest → CategoryService.updateCategory 위임 + CategoryResponse 반환")
    void update_delegates_to_service_and_returns_dto() {
        Category cat = categoryWithId(3L, "수정됨", "modified");
        when(categoryService.updateCategory(3L, "수정됨", "modified", null, 1)).thenReturn(cat);

        CategoryUpdateRequest req = new CategoryUpdateRequest("수정됨", "modified", null, 1);
        CategoryResponse result = categoryServiceResponse.update(3L, req);

        assertThat(result.categoryId()).isEqualTo(3L);
        assertThat(result.name()).isEqualTo("수정됨");
    }

    private Category categoryWithId(long id, String name, String slug) {
        Category cat = Category.of(name, slug, null, 0);
        try {
            var idField = Category.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(cat, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cat;
    }
}
