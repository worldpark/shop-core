package com.shop.shop.product.service;

import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.VariantManagementView;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.spi.SellerProductVariantFacade;
import com.shop.shop.product.spi.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SellerProductVariantFacadeImpl} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(a) email → userId 위임 (UserDirectory)</li>
 *   <li>(b) 두 서비스(ProductOptionService/ProductVariantService) 위임</li>
 *   <li>(c) Entity → DTO 변환</li>
 *   <li>(d) isAdmin 전달</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerProductVariantFacadeImplTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProductOptionService productOptionService;

    @Mock
    private ProductVariantService productVariantService;

    @Mock
    private OptionValueRepository optionValueRepository;

    @Mock
    private UserDirectory userDirectory;

    private SellerProductVariantFacade facade;

    private static final long ACTOR_ID = 2L;
    private static final String ACTOR_EMAIL = "seller@example.com";
    private static final long PRODUCT_ID = 10L;
    private static final long VARIANT_ID = 50L;
    private static final long OPTION_ID = 20L;

    @BeforeEach
    void setUp() {
        facade = new SellerProductVariantFacadeImpl(
                productService, productOptionService, productVariantService,
                optionValueRepository, userDirectory);
    }

    // ============================================================
    // getManagementView
    // ============================================================

    @Test
    @DisplayName("(a) getManagementView — UserDirectory.findUserIdByEmail로 actorId를 획득한다")
    void getManagementView_resolves_actor_id_from_email() {
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);
        when(productOptionService.listOptions(ACTOR_ID, false, PRODUCT_ID)).thenReturn(List.of());
        when(productVariantService.listVariants(ACTOR_ID, false, PRODUCT_ID)).thenReturn(List.of());

        facade.getManagementView(ACTOR_EMAIL, false, PRODUCT_ID);

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
    }

    @Test
    @DisplayName("(c) getManagementView — Product/Option/Variant Entity가 DTO로 변환된다")
    void getManagementView_maps_entities_to_dto() {
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);
        when(productOptionService.listOptions(ACTOR_ID, false, PRODUCT_ID)).thenReturn(List.of());
        when(productVariantService.listVariants(ACTOR_ID, false, PRODUCT_ID)).thenReturn(List.of());

        VariantManagementView result = facade.getManagementView(ACTOR_EMAIL, false, PRODUCT_ID);

        assertThat(result).isNotNull();
        assertThat(result.product().productId()).isEqualTo(PRODUCT_ID);
        assertThat(result.product().name()).isEqualTo("상품");
        assertThat(result.options()).isEmpty();
        assertThat(result.variants()).isEmpty();
    }

    @Test
    @DisplayName("(d) getManagementView — actorIsAdmin=true이면 true로 서비스에 전달된다")
    void getManagementView_passes_is_admin_true_to_services() {
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        when(productService.getOwnedProduct(ACTOR_ID, true, PRODUCT_ID)).thenReturn(product);
        when(productOptionService.listOptions(ACTOR_ID, true, PRODUCT_ID)).thenReturn(List.of());
        when(productVariantService.listVariants(ACTOR_ID, true, PRODUCT_ID)).thenReturn(List.of());

        facade.getManagementView(ACTOR_EMAIL, true, PRODUCT_ID);

        verify(productService).getOwnedProduct(ACTOR_ID, true, PRODUCT_ID);
        verify(productOptionService).listOptions(ACTOR_ID, true, PRODUCT_ID);
        verify(productVariantService).listVariants(ACTOR_ID, true, PRODUCT_ID);
    }

    // ============================================================
    // createOption
    // ============================================================

    @Test
    @DisplayName("(a)(b) createOption — email→userId 변환 후 ProductOptionService.createOption에 위임한다")
    void createOption_delegates_to_option_service() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);

        facade.createOption(ACTOR_EMAIL, false, PRODUCT_ID, "색상");

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
        verify(productOptionService).createOption(ACTOR_ID, false, PRODUCT_ID, "색상");
    }

    @Test
    @DisplayName("(d) createOption — actorIsAdmin=true이면 true로 전달된다")
    void createOption_passes_is_admin_flag() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);

        facade.createOption(ACTOR_EMAIL, true, PRODUCT_ID, "색상");

        verify(productOptionService).createOption(ACTOR_ID, true, PRODUCT_ID, "색상");
    }

    // ============================================================
    // createOptionValue
    // ============================================================

    @Test
    @DisplayName("(a)(b) createOptionValue — email→userId 변환 후 ProductOptionService.createOptionValue에 위임한다")
    void createOptionValue_delegates_to_option_service() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);

        facade.createOptionValue(ACTOR_EMAIL, false, PRODUCT_ID, OPTION_ID, "빨강");

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
        verify(productOptionService).createOptionValue(ACTOR_ID, false, PRODUCT_ID, OPTION_ID, "빨강");
    }

    // ============================================================
    // createVariant
    // ============================================================

    @Test
    @DisplayName("(a)(b) createVariant — email→userId 변환 후 ProductVariantService.createVariant에 위임한다")
    void createVariant_delegates_to_variant_service() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        List<Long> ids = List.of(101L);

        facade.createVariant(ACTOR_EMAIL, false, PRODUCT_ID, "SKU-001",
                new BigDecimal("10000"), 5, true, ids);

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
        verify(productVariantService).createVariant(ACTOR_ID, false, PRODUCT_ID, "SKU-001",
                new BigDecimal("10000"), 5, true, ids);
    }

    @Test
    @DisplayName("(d) createVariant — actorIsAdmin=true이면 true로 전달된다")
    void createVariant_passes_is_admin_flag() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);

        facade.createVariant(ACTOR_EMAIL, true, PRODUCT_ID, "SKU-ADM",
                new BigDecimal("5000"), 3, false, List.of());

        verify(productVariantService).createVariant(eq(ACTOR_ID), eq(true), eq(PRODUCT_ID),
                any(), any(), anyInt(), anyBoolean(), any());
    }

    // ============================================================
    // updateVariant
    // ============================================================

    @Test
    @DisplayName("(a)(b) updateVariant — email→userId 변환 후 ProductVariantService.updateVariant에 위임한다")
    void updateVariant_delegates_to_variant_service() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        List<Long> ids = List.of(101L);

        facade.updateVariant(ACTOR_EMAIL, false, PRODUCT_ID, VARIANT_ID, "SKU-UPD",
                new BigDecimal("20000"), 10, false, ids);

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
        verify(productVariantService).updateVariant(ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, "SKU-UPD",
                new BigDecimal("20000"), 10, false, ids);
    }

    @Test
    @DisplayName("(d) updateVariant — actorIsAdmin=true이면 true로 전달된다")
    void updateVariant_passes_is_admin_flag() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);

        facade.updateVariant(ACTOR_EMAIL, true, PRODUCT_ID, VARIANT_ID, "SKU-ADM",
                new BigDecimal("5000"), 3, false, List.of());

        verify(productVariantService).updateVariant(eq(ACTOR_ID), eq(true), eq(PRODUCT_ID), eq(VARIANT_ID),
                any(), any(), anyInt(), anyBoolean(), any());
    }

    // ============================================================
    // helpers
    // ============================================================

    private Product sampleProduct(long ownerId, long productId) {
        Product product = Product.create(ownerId, null, "상품", "설명", new BigDecimal("10000"));
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
