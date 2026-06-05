package com.shop.shop.product.service;

import com.shop.shop.common.exception.DuplicateOptionNameException;
import com.shop.shop.common.exception.DuplicateOptionValueException;
import com.shop.shop.common.exception.OptionNotFoundException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * ProductOptionService 단위 테스트.
 *
 * <p>Mockito로 Repository들을 격리해 도메인 불변식만 검증.
 * <ul>
 *   <li>V1: 옵션명 중복 금지</li>
 *   <li>V2: 옵션값 중복 금지</li>
 *   <li>V10: optionId가 productId 하위 리소스</li>
 *   <li>V12: 소유권 실패</li>
 *   <li>V13: 상품 미존재</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductOptionServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductOptionRepository productOptionRepository;

    @Mock
    private OptionValueRepository optionValueRepository;

    @InjectMocks
    private ProductService productService;

    private ProductOptionService productOptionService;

    private static final long SELLER_ID = 2L;
    private static final long OTHER_SELLER_ID = 5L;
    private static final long PRODUCT_ID = 10L;
    private static final long OPTION_ID = 20L;
    private static final long OTHER_PRODUCT_OPTION_ID = 99L;

    @BeforeEach
    void setUp() {
        productOptionService = new ProductOptionService(productService, productOptionRepository, optionValueRepository);
    }

    // ============================================================
    // createOption — 성공
    // ============================================================

    @Test
    @DisplayName("createOption — 성공: 옵션명이 중복되지 않으면 옵션이 생성된다")
    void createOption_success() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.existsByProductIdAndName(PRODUCT_ID, "색상")).thenReturn(false);
        when(productOptionRepository.save(any())).thenAnswer(inv -> {
            ProductOption opt = inv.getArgument(0);
            setId(opt, OPTION_ID);
            return opt;
        });

        ProductOption result = productOptionService.createOption(SELLER_ID, false, PRODUCT_ID, "색상");

        assertThat(result.getName()).isEqualTo("색상");
        assertThat(result.getProduct().getId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    @DisplayName("createOption — ADMIN 성공: ADMIN은 타인 상품의 옵션도 생성 가능")
    void createOption_admin_success() {
        Product product = productWithOwner(OTHER_SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.existsByProductIdAndName(PRODUCT_ID, "사이즈")).thenReturn(false);
        when(productOptionRepository.save(any())).thenAnswer(inv -> {
            ProductOption opt = inv.getArgument(0);
            setId(opt, OPTION_ID);
            return opt;
        });

        // actorId=SELLER_ID이지만 actorIsAdmin=true이므로 소유권 스킵
        ProductOption result = productOptionService.createOption(SELLER_ID, true, PRODUCT_ID, "사이즈");

        assertThat(result.getName()).isEqualTo("사이즈");
    }

    // ============================================================
    // createOption — 실패
    // ============================================================

    @Test
    @DisplayName("createOption — V1: 옵션명 중복 → DuplicateOptionNameException(409)")
    void createOption_fail_duplicate_name() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.existsByProductIdAndName(PRODUCT_ID, "색상")).thenReturn(true);

        assertThatThrownBy(() ->
                productOptionService.createOption(SELLER_ID, false, PRODUCT_ID, "색상"))
                .isInstanceOf(DuplicateOptionNameException.class)
                .satisfies(e -> assertThat(((DuplicateOptionNameException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("createOption — V12: 소유권 실패 → ProductAccessDeniedException(404)")
    void createOption_fail_ownership() {
        Product product = productWithOwner(OTHER_SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() ->
                productOptionService.createOption(SELLER_ID, false, PRODUCT_ID, "색상"))
                .isInstanceOf(ProductAccessDeniedException.class)
                .satisfies(e -> assertThat(((ProductAccessDeniedException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("createOption — V13: 상품 미존재 → ProductNotFoundException(404)")
    void createOption_fail_product_not_found() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                productOptionService.createOption(SELLER_ID, false, 999L, "색상"))
                .isInstanceOf(ProductNotFoundException.class)
                .satisfies(e -> assertThat(((ProductNotFoundException) e).getStatus().value()).isEqualTo(404));
    }

    // ============================================================
    // createOptionValue — 성공
    // ============================================================

    @Test
    @DisplayName("createOptionValue — 성공: 옵션값이 중복되지 않으면 옵션값이 생성된다")
    void createOptionValue_success() {
        Product product = productWithOwner(SELLER_ID);
        ProductOption option = productOptionWithId(OPTION_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option));
        when(optionValueRepository.existsByOptionIdAndValue(OPTION_ID, "빨강")).thenReturn(false);
        when(optionValueRepository.save(any())).thenAnswer(inv -> {
            OptionValue ov = inv.getArgument(0);
            setId(ov, 30L);
            return ov;
        });

        OptionValue result = productOptionService.createOptionValue(SELLER_ID, false, PRODUCT_ID, OPTION_ID, "빨강");

        assertThat(result.getValue()).isEqualTo("빨강");
        assertThat(result.getOption().getId()).isEqualTo(OPTION_ID);
    }

    // ============================================================
    // createOptionValue — 실패
    // ============================================================

    @Test
    @DisplayName("createOptionValue — V2: 옵션값 중복 → DuplicateOptionValueException(409)")
    void createOptionValue_fail_duplicate_value() {
        Product product = productWithOwner(SELLER_ID);
        ProductOption option = productOptionWithId(OPTION_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option));
        when(optionValueRepository.existsByOptionIdAndValue(OPTION_ID, "빨강")).thenReturn(true);

        assertThatThrownBy(() ->
                productOptionService.createOptionValue(SELLER_ID, false, PRODUCT_ID, OPTION_ID, "빨강"))
                .isInstanceOf(DuplicateOptionValueException.class)
                .satisfies(e -> assertThat(((DuplicateOptionValueException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("createOptionValue — V10: optionId가 다른 상품 소속 → OptionNotFoundException(404)")
    void createOptionValue_fail_option_not_in_product() {
        Product product = productWithOwner(SELLER_ID);
        Product otherProduct = productWithOwnerAndId(OTHER_SELLER_ID, 99L);
        ProductOption otherOption = productOptionWithId(OTHER_PRODUCT_OPTION_ID, otherProduct);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        // 옵션이 존재하지만 다른 상품 소속
        when(productOptionRepository.findById(OTHER_PRODUCT_OPTION_ID)).thenReturn(Optional.of(otherOption));

        assertThatThrownBy(() ->
                productOptionService.createOptionValue(SELLER_ID, false, PRODUCT_ID, OTHER_PRODUCT_OPTION_ID, "빨강"))
                .isInstanceOf(OptionNotFoundException.class)
                .satisfies(e -> assertThat(((OptionNotFoundException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("createOptionValue — V10: optionId 자체가 미존재 → OptionNotFoundException(404)")
    void createOptionValue_fail_option_not_found() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                productOptionService.createOptionValue(SELLER_ID, false, PRODUCT_ID, 999L, "빨강"))
                .isInstanceOf(OptionNotFoundException.class)
                .satisfies(e -> assertThat(((OptionNotFoundException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("createOptionValue — V12: 소유권 실패 → ProductAccessDeniedException(404)")
    void createOptionValue_fail_ownership() {
        Product product = productWithOwner(OTHER_SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() ->
                productOptionService.createOptionValue(SELLER_ID, false, PRODUCT_ID, OPTION_ID, "빨강"))
                .isInstanceOf(ProductAccessDeniedException.class)
                .satisfies(e -> assertThat(((ProductAccessDeniedException) e).getStatus().value()).isEqualTo(404));
    }

    // ============================================================
    // helpers
    // ============================================================

    private Product productWithOwner(long ownerId) {
        Product product = Product.create(ownerId, null, "상품", "설명", new BigDecimal("10000"));
        setId(product, PRODUCT_ID);
        return product;
    }

    private Product productWithOwnerAndId(long ownerId, long productId) {
        Product product = Product.create(ownerId, null, "타상품", "설명", new BigDecimal("10000"));
        setId(product, productId);
        return product;
    }

    private ProductOption productOptionWithId(long optionId, Product product) {
        ProductOption option = ProductOption.create(product, "색상");
        setId(option, optionId);
        return option;
    }

    private void setId(Object entity, long id) {
        try {
            var idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
