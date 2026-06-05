package com.shop.shop.product.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.DuplicateSkuException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ProductVariantService 단위 테스트.
 *
 * <p>Mockito로 Repository들을 격리해 도메인 불변식만 검증.
 * <ul>
 *   <li>V3: SKU 중복 금지</li>
 *   <li>V4: price ≥ 0</li>
 *   <li>V5: stock ≥ 0</li>
 *   <li>V6: 모든 optionValueId가 해당 productId 소속</li>
 *   <li>V7: 한 옵션당 최대 1개 optionValue</li>
 *   <li>V8: 상품의 모든 옵션을 커버</li>
 *   <li>V9: 동일 조합 중복 금지</li>
 *   <li>V11: variantId가 productId 하위 리소스</li>
 *   <li>V12: 소유권 실패</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductOptionRepository productOptionRepository;

    @Mock
    private OptionValueRepository optionValueRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @InjectMocks
    private ProductService productService;

    private ProductVariantService productVariantService;

    private static final long SELLER_ID = 2L;
    private static final long OTHER_SELLER_ID = 5L;
    private static final long PRODUCT_ID = 10L;
    private static final long VARIANT_ID = 50L;

    @BeforeEach
    void setUp() {
        productVariantService = new ProductVariantService(
                productService, productOptionRepository, optionValueRepository, productVariantRepository);
    }

    // ============================================================
    // createVariant — 성공
    // ============================================================

    @Test
    @DisplayName("createVariant — 성공: 옵션이 없는 상품에 variant 생성 (V8 충족 — 옵션 0개)")
    void createVariant_success_no_options() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-001")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.save(any())).thenAnswer(inv -> {
            ProductVariant v = inv.getArgument(0);
            setId(v, VARIANT_ID);
            return v;
        });

        ProductVariant result = productVariantService.createVariant(
                SELLER_ID, false, PRODUCT_ID, "SKU-001", new BigDecimal("10000"), 5, true, List.of());

        assertThat(result.getSku()).isEqualTo("SKU-001");
        assertThat(result.getPrice()).isEqualByComparingTo("10000");
        assertThat(result.getStock()).isEqualTo(5);
        assertThat(result.isActive()).isTrue();
    }

    @Test
    @DisplayName("createVariant — ADMIN 성공: ADMIN은 타인 상품의 variant도 생성 가능")
    void createVariant_admin_success() {
        Product product = productWithOwner(OTHER_SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-ADM")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.save(any())).thenAnswer(inv -> {
            ProductVariant v = inv.getArgument(0);
            setId(v, VARIANT_ID);
            return v;
        });

        // actorId=SELLER_ID이지만 actorIsAdmin=true이므로 소유권 스킵
        ProductVariant result = productVariantService.createVariant(
                SELLER_ID, true, PRODUCT_ID, "SKU-ADM", new BigDecimal("5000"), 3, false, List.of());

        assertThat(result.getSku()).isEqualTo("SKU-ADM");
    }

    @Test
    @DisplayName("createVariant — 성공: 옵션 조합이 올바르면 variant가 생성된다")
    void createVariant_success_with_options() {
        Product product = productWithOwner(SELLER_ID);
        ProductOption colorOption = productOptionWithId(1L, product, "색상");
        OptionValue redValue = optionValueWithId(101L, colorOption, "빨강");

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-RED")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of(redValue));
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of(colorOption));
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.save(any())).thenAnswer(inv -> {
            ProductVariant v = inv.getArgument(0);
            setId(v, VARIANT_ID);
            return v;
        });

        ProductVariant result = productVariantService.createVariant(
                SELLER_ID, false, PRODUCT_ID, "SKU-RED", new BigDecimal("10000"), 5, true, List.of(101L));

        assertThat(result.getSku()).isEqualTo("SKU-RED");
        assertThat(result.getOptionValues()).hasSize(1);
    }

    // ============================================================
    // createVariant — 실패
    // ============================================================

    @Test
    @DisplayName("createVariant — V3: SKU 중복 → DuplicateSkuException(409)")
    void createVariant_fail_duplicate_sku() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-DUP")).thenReturn(true);

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-DUP", new BigDecimal("10000"), 5, true, List.of()))
                .isInstanceOf(DuplicateSkuException.class)
                .satisfies(e -> assertThat(((DuplicateSkuException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("createVariant — V4: price 음수 → BusinessException(400)")
    void createVariant_fail_negative_price() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-NEG")).thenReturn(false);

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-NEG", new BigDecimal("-1"), 5, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("createVariant — V5: stock 음수 → BusinessException(400)")
    void createVariant_fail_negative_stock() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-NEG")).thenReturn(false);

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-NEG", new BigDecimal("1000"), -1, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("createVariant — V6: 타 상품 optionValueId → BusinessException(400)")
    void createVariant_fail_option_value_not_in_product() {
        Product product = productWithOwner(SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-X")).thenReturn(false);
        // 상품 소속 optionValue가 없음 → 요청한 999L이 유효하지 않음
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of());

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-X", new BigDecimal("1000"), 5, true, List.of(999L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("createVariant — V7: 한 옵션에 2개 값 선택 → BusinessException(400)")
    void createVariant_fail_two_values_for_one_option() {
        Product product = productWithOwner(SELLER_ID);
        ProductOption colorOption = productOptionWithId(1L, product, "색상");
        OptionValue redValue = optionValueWithId(101L, colorOption, "빨강");
        OptionValue blueValue = optionValueWithId(102L, colorOption, "파랑");

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-2V")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of(redValue, blueValue));

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-2V", new BigDecimal("1000"), 5, true,
                        List.of(101L, 102L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("createVariant — V8: 필수 옵션 누락 → BusinessException(400)")
    void createVariant_fail_missing_required_option() {
        Product product = productWithOwner(SELLER_ID);
        ProductOption colorOption = productOptionWithId(1L, product, "색상");
        ProductOption sizeOption = productOptionWithId(2L, product, "사이즈");
        OptionValue redValue = optionValueWithId(101L, colorOption, "빨강");
        // sizeOption에 해당하는 값은 선택하지 않음

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-MISS")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of(redValue));
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(
                List.of(colorOption, sizeOption));

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-MISS", new BigDecimal("1000"), 5, true,
                        List.of(101L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("createVariant — V9: 동일 조합 중복 → BusinessException(409)")
    void createVariant_fail_duplicate_combination() {
        Product product = productWithOwner(SELLER_ID);
        ProductOption colorOption = productOptionWithId(1L, product, "색상");
        OptionValue redValue = optionValueWithId(101L, colorOption, "빨강");

        // 기존에 동일 조합이 있음
        ProductVariant existing = ProductVariant.create(product, "SKU-EXIST",
                new BigDecimal("1000"), 5, true, Set.of(redValue));
        setId(existing, 40L);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-NEW")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of(redValue));
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of(colorOption));
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(existing));

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-NEW", new BigDecimal("1000"), 5, true,
                        List.of(101L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("createVariant — V12: 소유권 실패 → ProductAccessDeniedException(404)")
    void createVariant_fail_ownership() {
        Product product = productWithOwner(OTHER_SELLER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() ->
                productVariantService.createVariant(
                        SELLER_ID, false, PRODUCT_ID, "SKU-X", new BigDecimal("1000"), 5, true, List.of()))
                .isInstanceOf(ProductAccessDeniedException.class)
                .satisfies(e -> assertThat(((ProductAccessDeniedException) e).getStatus().value()).isEqualTo(404));
    }

    // ============================================================
    // updateVariant — 실패
    // ============================================================

    @Test
    @DisplayName("updateVariant — V11: variantId가 다른 상품 소속 → VariantNotFoundException(404)")
    void updateVariant_fail_variant_not_in_product() {
        Product product = productWithOwner(SELLER_ID);
        Product otherProduct = productWithOwnerAndId(OTHER_SELLER_ID, 99L);

        // variant가 존재하지만 다른 상품 소속
        ProductVariant otherVariant = ProductVariant.create(otherProduct, "SKU-OTHER",
                new BigDecimal("1000"), 5, true, Collections.emptySet());
        setId(otherVariant, VARIANT_ID);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(otherVariant));

        assertThatThrownBy(() ->
                productVariantService.updateVariant(
                        SELLER_ID, false, PRODUCT_ID, VARIANT_ID, "SKU-UPD",
                        new BigDecimal("2000"), 3, true, List.of()))
                .isInstanceOf(VariantNotFoundException.class)
                .satisfies(e -> assertThat(((VariantNotFoundException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("updateVariant — V3(자기제외): 자기 자신과 동일한 SKU는 중복이 아니다")
    void updateVariant_success_same_sku_self_exclude() {
        Product product = productWithOwner(SELLER_ID);
        ProductVariant variant = ProductVariant.create(product, "SKU-SAME",
                new BigDecimal("1000"), 5, true, Collections.emptySet());
        setId(variant, VARIANT_ID);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));
        when(productVariantRepository.existsBySkuAndIdNot("SKU-SAME", VARIANT_ID)).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(variant));

        // 자기 자신의 SKU로 업데이트 → 중복 아님
        ProductVariant result = productVariantService.updateVariant(
                SELLER_ID, false, PRODUCT_ID, VARIANT_ID, "SKU-SAME",
                new BigDecimal("2000"), 10, false, List.of());

        assertThat(result.getPrice()).isEqualByComparingTo("2000");
        assertThat(result.getStock()).isEqualTo(10);
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

    private ProductOption productOptionWithId(long optionId, Product product, String name) {
        ProductOption option = ProductOption.create(product, name);
        setId(option, optionId);
        return option;
    }

    private OptionValue optionValueWithId(long id, ProductOption option, String value) {
        OptionValue ov = OptionValue.create(option, value);
        setId(ov, id);
        return ov;
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
