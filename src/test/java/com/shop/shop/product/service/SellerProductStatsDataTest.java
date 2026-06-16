package com.shop.shop.product.service;

import com.shop.shop.product.domain.Product;
import com.shop.shop.product.dto.ProductStockSum;
import com.shop.shop.product.dto.SellerProductStatsData;
import com.shop.shop.product.dto.SellerProductSummaryView;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.product.spi.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SellerProductFacadeImpl#getMyProductStatsData} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>소유 상품 목록 + 재고 합계 맵 + variantId 매핑 정확성</li>
 *   <li>variant 없는 상품 — stockByProduct 맵에 키 없음 (totalStock=0 처리는 assembler 책임)</li>
 *   <li>productIds 비면 variantRepository 미호출</li>
 *   <li>totalElements 정확히 반환</li>
 *   <li>IDOR: ownerId 기준 소유 상품만 조회 (UserDirectory→ownerId 변환 후 ProductService 위임)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerProductStatsDataTest {

    @Mock
    private ProductService productService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private UserDirectory userDirectory;

    @Mock
    private ProductVariantRepository productVariantRepository;

    private SellerProductFacade facade;

    private static final long OWNER_ID = 2L;
    private static final String ACTOR_EMAIL = "seller@example.com";
    private static final long PRODUCT_ID_1 = 10L;
    private static final long PRODUCT_ID_2 = 20L;
    private static final long VARIANT_ID_1 = 100L;
    private static final long VARIANT_ID_2 = 101L;
    private static final long VARIANT_ID_3 = 200L;

    @BeforeEach
    void setUp() {
        facade = new SellerProductFacadeImpl(productService, categoryService, userDirectory, productVariantRepository);
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(OWNER_ID);
    }

    // ============================================================
    // 기본 조회
    // ============================================================

    @Test
    @DisplayName("소유 상품 목록이 SellerProductSummaryView로 변환된다")
    void getMyProductStatsData_products_mapped_to_summary_views() {
        Pageable pageable = PageRequest.of(0, 10);
        Product product1 = createProduct(OWNER_ID, PRODUCT_ID_1, "상품1");
        Product product2 = createProduct(OWNER_ID, PRODUCT_ID_2, "상품2");
        when(productService.getMyProducts(OWNER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(product1, product2), pageable, 2));
        when(productVariantRepository.findStockSumsByProductIdIn(any())).thenReturn(List.of());
        when(productVariantRepository.findVariantProductMappingsByProductIdIn(any())).thenReturn(List.of());

        SellerProductStatsData result = facade.getMyProductStatsData(ACTOR_EMAIL, pageable);

        assertThat(result.products()).hasSize(2);
        assertThat(result.products().get(0).productId()).isEqualTo(PRODUCT_ID_1);
        assertThat(result.products().get(1).productId()).isEqualTo(PRODUCT_ID_2);
    }

    @Test
    @DisplayName("totalElements가 정확히 반환된다")
    void getMyProductStatsData_returns_correct_total_elements() {
        Pageable pageable = PageRequest.of(0, 10);
        Product product = createProduct(OWNER_ID, PRODUCT_ID_1, "상품1");
        when(productService.getMyProducts(OWNER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        when(productVariantRepository.findStockSumsByProductIdIn(any())).thenReturn(List.of());
        when(productVariantRepository.findVariantProductMappingsByProductIdIn(any())).thenReturn(List.of());

        SellerProductStatsData result = facade.getMyProductStatsData(ACTOR_EMAIL, pageable);

        assertThat(result.totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("재고 합계 맵이 productId → totalStock으로 올바르게 구성된다")
    void getMyProductStatsData_stock_map_contains_correct_product_stock() {
        Pageable pageable = PageRequest.of(0, 10);
        Product product1 = createProduct(OWNER_ID, PRODUCT_ID_1, "상품1");
        Product product2 = createProduct(OWNER_ID, PRODUCT_ID_2, "상품2");
        when(productService.getMyProducts(OWNER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(product1, product2), pageable, 2));
        when(productVariantRepository.findStockSumsByProductIdIn(any())).thenReturn(List.of(
                new ProductStockSum(PRODUCT_ID_1, 50L),
                new ProductStockSum(PRODUCT_ID_2, 30L)
        ));
        when(productVariantRepository.findVariantProductMappingsByProductIdIn(any())).thenReturn(List.of());

        SellerProductStatsData result = facade.getMyProductStatsData(ACTOR_EMAIL, pageable);

        assertThat(result.stockByProduct()).containsEntry(PRODUCT_ID_1, 50L);
        assertThat(result.stockByProduct()).containsEntry(PRODUCT_ID_2, 30L);
    }

    @Test
    @DisplayName("variant 없는 상품은 stockByProduct 맵에 키가 없다 (totalStock=0은 assembler 처리)")
    void getMyProductStatsData_product_without_variants_has_no_key_in_stock_map() {
        Pageable pageable = PageRequest.of(0, 10);
        Product product = createProduct(OWNER_ID, PRODUCT_ID_1, "variant없는상품");
        when(productService.getMyProducts(OWNER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        // 재고 집계 결과 없음 (variant가 없으므로)
        when(productVariantRepository.findStockSumsByProductIdIn(any())).thenReturn(List.of());
        when(productVariantRepository.findVariantProductMappingsByProductIdIn(any())).thenReturn(List.of());

        SellerProductStatsData result = facade.getMyProductStatsData(ACTOR_EMAIL, pageable);

        assertThat(result.stockByProduct()).doesNotContainKey(PRODUCT_ID_1);
    }

    @Test
    @DisplayName("variantId 매핑이 올바르게 반환된다")
    void getMyProductStatsData_variant_mappings_returned_correctly() {
        Pageable pageable = PageRequest.of(0, 10);
        Product product1 = createProduct(OWNER_ID, PRODUCT_ID_1, "상품1");
        Product product2 = createProduct(OWNER_ID, PRODUCT_ID_2, "상품2");
        when(productService.getMyProducts(OWNER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(product1, product2), pageable, 2));
        when(productVariantRepository.findStockSumsByProductIdIn(any())).thenReturn(List.of());
        when(productVariantRepository.findVariantProductMappingsByProductIdIn(any())).thenReturn(List.of(
                new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1),
                new VariantProductMapping(VARIANT_ID_2, PRODUCT_ID_1),
                new VariantProductMapping(VARIANT_ID_3, PRODUCT_ID_2)
        ));

        SellerProductStatsData result = facade.getMyProductStatsData(ACTOR_EMAIL, pageable);

        assertThat(result.variantMappings()).hasSize(3);
        assertThat(result.variantMappings())
                .extracting(VariantProductMapping::variantId)
                .containsExactlyInAnyOrder(VARIANT_ID_1, VARIANT_ID_2, VARIANT_ID_3);
    }

    // ============================================================
    // 빈 결과
    // ============================================================

    @Test
    @DisplayName("소유 상품이 없으면 variant 관련 repository를 호출하지 않는다")
    void getMyProductStatsData_no_products_skips_variant_repository_calls() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productService.getMyProducts(OWNER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        SellerProductStatsData result = facade.getMyProductStatsData(ACTOR_EMAIL, pageable);

        verify(productVariantRepository, never()).findStockSumsByProductIdIn(any());
        verify(productVariantRepository, never()).findVariantProductMappingsByProductIdIn(any());
        assertThat(result.products()).isEmpty();
        assertThat(result.stockByProduct()).isEmpty();
        assertThat(result.variantMappings()).isEmpty();
    }

    // ============================================================
    // IDOR 방지: ownerId 변환
    // ============================================================

    @Test
    @DisplayName("IDOR: actorEmail이 ownerId로 변환되어 ProductService에 전달된다")
    void getMyProductStatsData_uses_owner_id_from_user_directory() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productService.getMyProducts(OWNER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        facade.getMyProductStatsData(ACTOR_EMAIL, pageable);

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
        verify(productService).getMyProducts(OWNER_ID, pageable);
    }

    // ============================================================
    // helpers
    // ============================================================

    private Product createProduct(long ownerId, long productId, String name) {
        Product product = Product.create(ownerId, null, name, null, new BigDecimal("10000"));
        try {
            var idField = Product.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(product, productId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return product;
    }
}
