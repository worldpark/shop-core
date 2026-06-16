package com.shop.shop.product.service;

import com.shop.shop.product.domain.Category;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.ProductFormView;
import com.shop.shop.product.dto.SellerProductSummaryView;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.product.spi.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SellerProductFacadeImpl} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(a) ProductService/CategoryService/UserDirectory에 올바르게 위임하는지</li>
 *   <li>(b) status String → ProductStatus 변환 (update)</li>
 *   <li>(c) actorEmail → actorId 해석 (UserDirectory 호출)</li>
 *   <li>(d) Product Entity → ProductFormView DTO 매핑 (status=String)</li>
 *   <li>(e) Category Entity → CategoryResponse DTO 매핑</li>
 *   <li>(f) productStatusNames() — ProductStatus.name() 목록 반환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerProductFacadeImplTest {

    @Mock
    private ProductService productService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private UserDirectory userDirectory;

    @Mock
    private com.shop.shop.product.repository.ProductVariantRepository productVariantRepository;

    private SellerProductFacade facade;

    private static final long ACTOR_ID = 2L;
    private static final String ACTOR_EMAIL = "seller@example.com";
    private static final long PRODUCT_ID = 10L;

    @BeforeEach
    void setUp() {
        facade = new SellerProductFacadeImpl(productService, categoryService, userDirectory, productVariantRepository);
        // userDirectory stub은 actorEmail을 사용하는 테스트에서만 개별 설정
        // (listCategories/productStatusNames는 userDirectory를 호출하지 않으므로 공통 설정 제외)
    }

    // ============================================================
    // getMyProducts
    // ============================================================

    @Test
    @DisplayName("(c) getMyProducts — UserDirectory.findUserIdByEmail로 actorId를 획득한다")
    void getMyProducts_resolves_actor_id_from_email_via_user_directory() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Pageable pageable = PageRequest.of(0, 10);
        when(productService.getMyProducts(ACTOR_ID, pageable)).thenReturn(Page.empty(pageable));

        facade.getMyProducts(ACTOR_EMAIL, pageable);

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
    }

    @Test
    @DisplayName("(a) getMyProducts — 획득한 ownerId로 ProductService.getMyProducts에 위임한다 (IDOR)")
    void getMyProducts_delegates_to_product_service_with_resolved_owner_id() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Pageable pageable = PageRequest.of(0, 10);
        when(productService.getMyProducts(eq(ACTOR_ID), eq(pageable))).thenReturn(Page.empty(pageable));

        facade.getMyProducts(ACTOR_EMAIL, pageable);

        ArgumentCaptor<Long> ownerCaptor = ArgumentCaptor.forClass(Long.class);
        verify(productService).getMyProducts(ownerCaptor.capture(), eq(pageable));
        assertThat(ownerCaptor.getValue()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("(d) getMyProducts — Page<Product>가 Page<SellerProductSummaryView>로 매핑된다 (Entity 미노출)")
    void getMyProducts_maps_product_page_to_dto_page_without_entity_leak() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Pageable pageable = PageRequest.of(0, 10);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        Page<Product> productPage = new PageImpl<>(List.of(product), pageable, 1);
        when(productService.getMyProducts(ACTOR_ID, pageable)).thenReturn(productPage);

        Page<SellerProductSummaryView> result = facade.getMyProducts(ACTOR_EMAIL, pageable);

        assertThat(result.getContent()).hasSize(1);
        SellerProductSummaryView view = result.getContent().get(0);
        // Entity 미노출: 반환 원소 타입이 SellerProductSummaryView여야 함
        assertThat(view).isInstanceOf(SellerProductSummaryView.class);
    }

    @Test
    @DisplayName("(d) getMyProducts — DTO 필드 매핑(productId·name·status(name())·basePrice·createdAt) 검증")
    void getMyProducts_maps_dto_fields_correctly() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Pageable pageable = PageRequest.of(0, 10);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        Page<Product> productPage = new PageImpl<>(List.of(product), pageable, 1);
        when(productService.getMyProducts(ACTOR_ID, pageable)).thenReturn(productPage);

        Page<SellerProductSummaryView> result = facade.getMyProducts(ACTOR_EMAIL, pageable);

        SellerProductSummaryView view = result.getContent().get(0);
        assertThat(view.productId()).isEqualTo(PRODUCT_ID);
        assertThat(view.name()).isEqualTo("상품");
        assertThat(view.status()).isEqualTo("DRAFT");              // ProductStatus.name() — String
        assertThat(view.status()).isInstanceOf(String.class);      // enum 타입 아님
        assertThat(view.basePrice()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("(d) getMyProducts — 빈 Page를 그대로 반환한다 (예외 없음)")
    void getMyProducts_returns_empty_page_when_no_products() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Pageable pageable = PageRequest.of(0, 10);
        when(productService.getMyProducts(ACTOR_ID, pageable)).thenReturn(Page.empty(pageable));

        Page<SellerProductSummaryView> result = facade.getMyProducts(ACTOR_EMAIL, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    // ============================================================
    // listCategories
    // ============================================================

    @Test
    @DisplayName("(a)(e) listCategories — CategoryService.list()를 호출하고 CategoryResponse DTO로 매핑한다")
    void listCategories_calls_category_service_and_maps_to_dto() {
        Category category = sampleCategory(1L, "전자기기", "electronics");
        when(categoryService.list()).thenReturn(List.of(category));

        List<CategoryResponse> result = facade.listCategories();

        verify(categoryService).list();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(1L);
        assertThat(result.get(0).name()).isEqualTo("전자기기");
        assertThat(result.get(0).slug()).isEqualTo("electronics");
    }

    @Test
    @DisplayName("(e) listCategories — 카테고리가 없으면 빈 목록을 반환한다")
    void listCategories_returns_empty_list_when_no_categories() {
        when(categoryService.list()).thenReturn(List.of());

        List<CategoryResponse> result = facade.listCategories();

        assertThat(result).isEmpty();
    }

    // ============================================================
    // productStatusNames
    // ============================================================

    @Test
    @DisplayName("(f) productStatusNames — ProductStatus 모든 상수의 name() 목록을 반환한다")
    void productStatusNames_returns_all_status_names_as_strings() {
        List<String> names = facade.productStatusNames();

        assertThat(names).containsExactlyInAnyOrder("DRAFT", "ON_SALE", "SOLD_OUT", "HIDDEN");
    }

    @Test
    @DisplayName("(f) productStatusNames — 반환값이 String 타입이다 (enum 타입 아님)")
    void productStatusNames_returns_string_type_not_enum() {
        List<String> names = facade.productStatusNames();

        // 반환 타입이 List<String>임을 타입 시스템으로 보장 — 컴파일 자체가 검증
        assertThat(names).isNotEmpty();
        names.forEach(name -> assertThat(name).isInstanceOf(String.class));
    }

    // ============================================================
    // register
    // ============================================================

    @Test
    @DisplayName("(c) register — UserDirectory.findUserIdByEmail로 actorId를 획득한다")
    void register_resolves_actor_id_from_email_via_user_directory() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(productService.register(anyLong(), any(), any(), any(), any())).thenReturn(product);

        facade.register(ACTOR_EMAIL, null, "상품A", "설명", new BigDecimal("10000"));

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
    }

    @Test
    @DisplayName("(a) register — 획득한 actorId로 ProductService.register에 위임하고 productId를 반환한다")
    void register_delegates_to_product_service_and_returns_product_id() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(productService.register(eq(ACTOR_ID), eq(null), eq("상품A"), eq("설명"), eq(new BigDecimal("10000"))))
                .thenReturn(product);

        long productId = facade.register(ACTOR_EMAIL, null, "상품A", "설명", new BigDecimal("10000"));

        assertThat(productId).isEqualTo(PRODUCT_ID);
        verify(productService).register(ACTOR_ID, null, "상품A", "설명", new BigDecimal("10000"));
    }

    @Test
    @DisplayName("(a) register — categoryId가 있을 때 ProductService.register에 categoryId를 전달한다")
    void register_passes_category_id_to_product_service() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(productService.register(anyLong(), eq(5L), any(), any(), any())).thenReturn(product);

        facade.register(ACTOR_EMAIL, 5L, "상품B", null, new BigDecimal("5000"));

        ArgumentCaptor<Long> categoryCaptor = ArgumentCaptor.forClass(Long.class);
        verify(productService).register(eq(ACTOR_ID), categoryCaptor.capture(), any(), any(), any());
        assertThat(categoryCaptor.getValue()).isEqualTo(5L);
    }

    // ============================================================
    // getForEdit
    // ============================================================

    @Test
    @DisplayName("(c) getForEdit — UserDirectory.findUserIdByEmail로 actorId를 획득한다")
    void getForEdit_resolves_actor_id_from_email() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(productService.getForEdit(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        facade.getForEdit(ACTOR_EMAIL, false, PRODUCT_ID);

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
    }

    @Test
    @DisplayName("(a) getForEdit — actorId·actorIsAdmin·productId로 ProductService.getForEdit에 위임한다")
    void getForEdit_delegates_to_product_service_with_actor_id_and_is_admin() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(productService.getForEdit(ACTOR_ID, true, PRODUCT_ID)).thenReturn(product);

        facade.getForEdit(ACTOR_EMAIL, true, PRODUCT_ID);

        verify(productService).getForEdit(ACTOR_ID, true, PRODUCT_ID);
    }

    @Test
    @DisplayName("(d) getForEdit — Product Entity가 ProductFormView DTO로 매핑된다 (status=String)")
    void getForEdit_maps_product_entity_to_product_form_view_dto_with_string_status() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(productService.getForEdit(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        ProductFormView result = facade.getForEdit(ACTOR_EMAIL, false, PRODUCT_ID);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("상품");
        assertThat(result.basePrice()).isEqualByComparingTo(new BigDecimal("10000"));
        // status는 String으로 변환되어야 한다 (enum 타입 아님)
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.status()).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("(d) getForEdit — categoryId=null인 미분류 상품도 올바르게 DTO로 변환한다")
    void getForEdit_maps_product_with_null_category_correctly() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Product product = sampleProduct(ACTOR_ID, PRODUCT_ID);
        when(productService.getForEdit(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        ProductFormView result = facade.getForEdit(ACTOR_EMAIL, false, PRODUCT_ID);

        assertThat(result.categoryId()).isNull();
    }

    // ============================================================
    // update
    // ============================================================

    @Test
    @DisplayName("(c) update — UserDirectory.findUserIdByEmail로 actorId를 획득한다")
    void update_resolves_actor_id_from_email() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        facade.update(ACTOR_EMAIL, false, PRODUCT_ID, null, "수정상품", "설명", new BigDecimal("20000"), "ON_SALE");

        verify(userDirectory).findUserIdByEmail(ACTOR_EMAIL);
    }

    @Test
    @DisplayName("(b) update — status \"ON_SALE\" String이 ProductStatus.ON_SALE로 변환되어 위임된다")
    void update_converts_status_string_to_product_status_enum() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        facade.update(ACTOR_EMAIL, false, PRODUCT_ID, null, "수정상품", "설명", new BigDecimal("20000"), "ON_SALE");

        ArgumentCaptor<ProductStatus> statusCaptor = ArgumentCaptor.forClass(ProductStatus.class);
        verify(productService).update(eq(ACTOR_ID), eq(false), eq(PRODUCT_ID),
                any(), any(), any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("(b) update — status \"HIDDEN\" String이 ProductStatus.HIDDEN으로 변환되어 위임된다")
    void update_converts_hidden_string_to_product_status_hidden() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        facade.update(ACTOR_EMAIL, false, PRODUCT_ID, null, "수정상품", "설명", new BigDecimal("20000"), "HIDDEN");

        verify(productService).update(eq(ACTOR_ID), eq(false), eq(PRODUCT_ID),
                any(), any(), any(), any(), eq(ProductStatus.HIDDEN));
    }

    @Test
    @DisplayName("(a) update — actorId·actorIsAdmin·productId 포함 모든 인자가 ProductService.update에 전달된다")
    void update_delegates_all_arguments_to_product_service() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        Long categoryId = 3L;
        String name = "수정상품";
        String description = "수정설명";
        BigDecimal basePrice = new BigDecimal("30000");

        facade.update(ACTOR_EMAIL, true, PRODUCT_ID, categoryId, name, description, basePrice, "SOLD_OUT");

        verify(productService).update(ACTOR_ID, true, PRODUCT_ID, categoryId, name, description, basePrice,
                ProductStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("(a) update — actorIsAdmin=true이면 ProductService에 true로 전달한다 (소유권 스킵 보존)")
    void update_passes_actor_is_admin_flag_to_product_service() {
        when(userDirectory.findUserIdByEmail(ACTOR_EMAIL)).thenReturn(ACTOR_ID);
        facade.update(ACTOR_EMAIL, true, PRODUCT_ID, null, "상품", null, new BigDecimal("10000"), "DRAFT");

        verify(productService).update(eq(ACTOR_ID), eq(true), eq(PRODUCT_ID),
                any(), any(), any(), any(), any());
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

    private Category sampleCategory(long id, String name, String slug) {
        Category category = Category.of(name, slug, null, 1);
        try {
            var idField = Category.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(category, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return category;
    }
}
