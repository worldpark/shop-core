package com.shop.shop.product.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.CategoryNotFoundException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ProductService 단위 테스트.
 * Mockito로 Repository들을 격리해 도메인 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    // ============================================================
    // register — 성공
    // ============================================================

    @Test
    @DisplayName("register — 성공: status는 항상 DRAFT")
    void register_success_status_is_draft() {
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.register(1L, null, "상품A", "설명", new BigDecimal("10000"));

        assertThat(result.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(result.getOwnerId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("상품A");
    }

    @Test
    @DisplayName("register — 성공: categoryId가 있으면 category 존재 검증 후 설정")
    void register_success_with_category() {
        Category cat = categoryWithId(5L);
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(cat));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.register(1L, 5L, "상품B", null, new BigDecimal("5000"));

        assertThat(result.getCategory()).isNotNull();
        assertThat(result.getCategory().getId()).isEqualTo(5L);
    }

    // ============================================================
    // register — 실패
    // ============================================================

    @Test
    @DisplayName("register — categoryId 미존재 → CategoryNotFoundException(404)")
    void register_fail_category_not_found() {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                productService.register(1L, 999L, "상품", null, new BigDecimal("1000")))
                .isInstanceOf(CategoryNotFoundException.class)
                .satisfies(e -> assertThat(((CategoryNotFoundException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("register — basePrice 음수 → BusinessException(400)")
    void register_fail_negative_base_price() {
        assertThatThrownBy(() ->
                productService.register(1L, null, "상품", null, new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
    }

    // ============================================================
    // update — 성공
    // ============================================================

    @Test
    @DisplayName("update — 성공: 소유자 본인이 수정")
    void update_success_by_owner() {
        Product product = productWithOwner(1L);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        Product result = productService.update(1L, false, 10L, null, "수정상품", "수정설명",
                new BigDecimal("20000"), ProductStatus.ON_SALE);

        assertThat(result.getName()).isEqualTo("수정상품");
        assertThat(result.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("update — 성공: ADMIN(actorIsAdmin=true)은 타인 상품도 수정 가능")
    void update_success_by_admin() {
        Product product = productWithOwner(99L); // 소유자는 99
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        // actorId=1(다른 사람), actorIsAdmin=true
        Product result = productService.update(1L, true, 10L, null, "ADMIN수정", null,
                new BigDecimal("5000"), ProductStatus.HIDDEN);

        assertThat(result.getName()).isEqualTo("ADMIN수정");
    }

    // ============================================================
    // update — 실패
    // ============================================================

    @Test
    @DisplayName("update — 상품 미존재 → ProductNotFoundException(404)")
    void update_fail_product_not_found() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                productService.update(1L, false, 999L, null, "수정", null,
                        new BigDecimal("1000"), ProductStatus.DRAFT))
                .isInstanceOf(ProductNotFoundException.class)
                .satisfies(e -> assertThat(((ProductNotFoundException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("update — 타 판매자 수정 → ProductAccessDeniedException(404, 존재 은닉)")
    void update_fail_other_seller_access_denied() {
        Product product = productWithOwner(99L); // 소유자 99
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        // actorId=1(타 판매자), actorIsAdmin=false
        assertThatThrownBy(() ->
                productService.update(1L, false, 10L, null, "수정", null,
                        new BigDecimal("1000"), ProductStatus.DRAFT))
                .isInstanceOf(ProductAccessDeniedException.class)
                .satisfies(e -> assertThat(((ProductAccessDeniedException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("update — basePrice 음수 → BusinessException(400)")
    void update_fail_negative_base_price() {
        Product product = productWithOwner(1L);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() ->
                productService.update(1L, false, 10L, null, "수정", null,
                        new BigDecimal("-1"), ProductStatus.DRAFT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
    }

    // ============================================================
    // getForEdit
    // ============================================================

    @Test
    @DisplayName("getForEdit — 성공: 소유자 본인 조회")
    void getForEdit_success_by_owner() {
        Product product = productWithOwner(1L);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        Product result = productService.getForEdit(1L, false, 10L);

        assertThat(result.getOwnerId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getForEdit — 성공: ADMIN은 타인 상품도 조회 가능")
    void getForEdit_success_by_admin() {
        Product product = productWithOwner(99L);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        Product result = productService.getForEdit(1L, true, 10L);

        assertThat(result.getOwnerId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("getForEdit — 타 판매자 → ProductAccessDeniedException(404)")
    void getForEdit_fail_other_seller() {
        Product product = productWithOwner(99L);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.getForEdit(1L, false, 10L))
                .isInstanceOf(ProductAccessDeniedException.class);
    }

    @Test
    @DisplayName("getForEdit — 상품 미존재 → ProductNotFoundException(404)")
    void getForEdit_fail_not_found() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getForEdit(1L, false, 999L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    // ============================================================
    // helpers
    // ============================================================

    private Product productWithOwner(long ownerId) {
        Product product = Product.create(ownerId, null, "상품", "설명", new BigDecimal("1000"));
        try {
            var idField = Product.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(product, 10L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return product;
    }

    private Category categoryWithId(long id) {
        Category cat = Category.of("테스트", "test-" + id, null, 0);
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
