package com.shop.shop.product.service;

import com.shop.shop.common.exception.CategoryNotFoundException;
import com.shop.shop.common.exception.DuplicateSlugException;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.repository.CategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CategoryService 단위 테스트.
 * Mockito로 CategoryRepository를 격리해 도메인 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    // ============================================================
    // list
    // ============================================================

    @Test
    @DisplayName("list — 카테고리 목록 조회: findAllByOrderBySortOrderAscIdAsc에 위임")
    void list_delegates_to_repository() {
        Category cat = Category.of("전자", "electronics", null, 0);
        when(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(cat));

        List<Category> result = categoryService.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("전자");
        verify(categoryRepository).findAllByOrderBySortOrderAscIdAsc();
    }

    // ============================================================
    // createCategory — 성공
    // ============================================================

    @Test
    @DisplayName("createCategory — 성공: slug 비중복, parent null(root)")
    void createCategory_success_root() {
        when(categoryRepository.existsBySlug("electronics")).thenReturn(false);
        Category saved = Category.of("전자", "electronics", null, 0);
        when(categoryRepository.save(any())).thenReturn(saved);

        Category result = categoryService.createCategory("전자", "electronics", null, 0);

        assertThat(result.getName()).isEqualTo("전자");
        assertThat(result.getSlug()).isEqualTo("electronics");
        assertThat(result.getParent()).isNull();
        verify(categoryRepository).save(any());
    }

    @Test
    @DisplayName("createCategory — 성공: parent 존재")
    void createCategory_success_with_parent() {
        Category parent = categoryWithId(1L, "전자", "electronics");
        when(categoryRepository.existsBySlug("mobile")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category result = categoryService.createCategory("모바일", "mobile", 1L, 1);

        assertThat(result.getSlug()).isEqualTo("mobile");
        assertThat(result.getParent()).isNotNull();
        assertThat(result.getParent().getId()).isEqualTo(1L);
    }

    // ============================================================
    // createCategory — 실패
    // ============================================================

    @Test
    @DisplayName("createCategory — slug 중복 → DuplicateSlugException(409)")
    void createCategory_fail_duplicate_slug() {
        when(categoryRepository.existsBySlug("electronics")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory("전자2", "electronics", null, 0))
                .isInstanceOf(DuplicateSlugException.class)
                .satisfies(e -> assertThat(((DuplicateSlugException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("createCategory — parent 미존재 → CategoryNotFoundException(404)")
    void createCategory_fail_parent_not_found() {
        when(categoryRepository.existsBySlug("mobile")).thenReturn(false);
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.createCategory("모바일", "mobile", 999L, 0))
                .isInstanceOf(CategoryNotFoundException.class)
                .satisfies(e -> assertThat(((CategoryNotFoundException) e).getStatus().value()).isEqualTo(404));
    }

    // ============================================================
    // updateCategory — 성공
    // ============================================================

    @Test
    @DisplayName("updateCategory — 성공: 자기 slug 유지(동일 slug)는 통과")
    void updateCategory_success_same_slug() {
        Category existing = categoryWithId(10L, "전자", "electronics");
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(existing));
        // 자기 제외 중복 없음
        when(categoryRepository.existsBySlugAndIdNot("electronics", 10L)).thenReturn(false);

        Category result = categoryService.updateCategory(10L, "전자(수정)", "electronics", null, 1);

        assertThat(result.getName()).isEqualTo("전자(수정)");
        assertThat(result.getSlug()).isEqualTo("electronics");
    }

    @Test
    @DisplayName("updateCategory — 성공: slug 변경(다른 slug, 중복 아님)")
    void updateCategory_success_new_slug() {
        Category existing = categoryWithId(10L, "전자", "electronics");
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsBySlugAndIdNot("electronics-new", 10L)).thenReturn(false);

        Category result = categoryService.updateCategory(10L, "전자", "electronics-new", null, 0);

        assertThat(result.getSlug()).isEqualTo("electronics-new");
    }

    // ============================================================
    // updateCategory — 실패
    // ============================================================

    @Test
    @DisplayName("updateCategory — 대상 미존재 → CategoryNotFoundException(404)")
    void updateCategory_fail_not_found() {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.updateCategory(999L, "test", "test-slug", null, 0))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    @DisplayName("updateCategory — slug 중복(자기 제외) → DuplicateSlugException(409)")
    void updateCategory_fail_duplicate_slug_excluding_self() {
        Category existing = categoryWithId(10L, "전자", "electronics");
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsBySlugAndIdNot("mobile", 10L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.updateCategory(10L, "모바일", "mobile", null, 0))
                .isInstanceOf(DuplicateSlugException.class);
    }

    // ============================================================
    // deleteCategory
    // ============================================================

    @Test
    @DisplayName("deleteCategory — 성공: findById → delete 1회 호출")
    void deleteCategory_success_calls_repository_delete() {
        Category existing = categoryWithId(10L, "전자", "electronics");
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(existing));

        categoryService.deleteCategory(10L);

        verify(categoryRepository).delete(existing);
    }

    @Test
    @DisplayName("deleteCategory — 존재X → CategoryNotFoundException(404), delete 미호출")
    void deleteCategory_not_found_throws_CategoryNotFoundException() {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory(999L))
                .isInstanceOf(CategoryNotFoundException.class)
                .satisfies(e -> assertThat(((CategoryNotFoundException) e).getStatus().value()).isEqualTo(404));

        verify(categoryRepository, never()).delete(any());
    }

    // ============================================================
    // helpers
    // ============================================================

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
