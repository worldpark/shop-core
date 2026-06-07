package com.shop.shop.view;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.ProductImageManagementView;
import com.shop.shop.product.dto.ProductImageResponse;
import com.shop.shop.product.dto.SellerProductRef;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.product.spi.SellerProductImageFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 이미지 관리 화면 HTML 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿(templates/seller/product-images.html)이
 * layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증한다.
 *
 * <p>SellerProductImageFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>공통 레이아웃(header·nav·footer) 포함</li>
 *   <li>CSRF 토큰 자동 주입 (_csrf 히든 필드)</li>
 *   <li>업로드 폼: enctype="multipart/form-data", action, 파일 필드(file)</li>
 *   <li>대표 지정 폼: action(primary), CSRF</li>
 *   <li>정렬 변경 폼: action(order), sortOrder 필드, CSRF</li>
 *   <li>삭제 폼: action(delete), CSRF</li>
 *   <li>이미지 목록: imageUrl 표시 (base URL 하드코딩 없이 DTO에서 출력)</li>
 *   <li>업로드 성공 redirect(302)</li>
 *   <li>flashSuccess/flashError 영역 포함</li>
 * </ul>
 *
 * <p>패턴: SellerProductVariantsRenderingTest 컨벤션 준수.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductImagesRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private OptionValueRepository optionValueRepository;

    @MockitoBean
    private ProductVariantRepository productVariantRepository;

    @MockitoBean
    private ProductImageRepository productImageRepository;

    @MockitoBean
    private CartRepository cartRepository;

    @MockitoBean
    private CartItemRepository cartItemRepository;

    @MockitoBean
    private InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private SellerProductImageFacade sellerProductImageFacade;

    private static final long PRODUCT_ID = 10L;
    private static final long IMAGE_ID = 50L;
    private static final String SELLER_EMAIL = "seller@example.com";
    private static final String BASE_URL = "/seller/products/" + PRODUCT_ID + "/images";
    private static final String IMAGE_URL = "http://localhost/assets/products/10/abc.jpg";

    /** footer 프래그먼트 식별 마커 */
    static final String FOOTER_MARKER = "2026 shop-core. All rights reserved.";

    @BeforeEach
    void setUp() {
        SellerProductRef productRef = new SellerProductRef(PRODUCT_ID, "테스트 상품");
        List<ProductImageResponse> images = List.of(
                new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/abc.jpg",
                        IMAGE_URL, 0, true)
        );
        ProductImageManagementView stubView = new ProductImageManagementView(productRef, images);

        when(sellerProductImageFacade.getManagementView(anyString(), anyBoolean(), anyLong()))
                .thenReturn(stubView);
    }

    // ============================================================
    // (I1) 공통 레이아웃
    // ============================================================

    @Test
    @DisplayName("(I1) GET /images — 공통 레이아웃: header·nav·footer 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_includes_layout_fragments() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("header(shop-core)가 포함되어야 함").contains("shop-core");
        assertThat(body).as("nav '홈' 마커가 포함되어야 함").contains("홈");
        assertThat(body).as("footer 마커가 포함되어야 함").contains(FOOTER_MARKER);
        assertThat(body).as("/css/app.css 링크가 포함되어야 함").contains("/css/app.css");
    }

    // ============================================================
    // (I2) CSRF 토큰
    // ============================================================

    @Test
    @DisplayName("(I2) GET /images — CSRF 토큰 자동 주입 (_csrf 히든 필드)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_includes_csrf_token() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("_csrf 토큰이 폼에 자동 주입되어야 함").contains("_csrf");
    }

    // ============================================================
    // (I3) 업로드 폼
    // ============================================================

    @Test
    @DisplayName("(I3) GET /images — 업로드 폼: multipart/form-data enctype, action, file 필드")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_renders_upload_form_with_multipart_enctype() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("업로드 폼에 enctype=multipart/form-data가 있어야 함")
                .contains("enctype=\"multipart/form-data\"");
        assertThat(body).as("업로드 폼 action이 /seller/products/{productId}/images 여야 함")
                .contains("action=\"/seller/products/" + PRODUCT_ID + "/images\"");
        assertThat(body).as("업로드 폼에 file 필드(name=file)가 있어야 함")
                .contains("name=\"file\"");
    }

    // ============================================================
    // (I4) 대표 지정 폼
    // ============================================================

    @Test
    @DisplayName("(I4) GET /images — 대표 지정 폼: action(/primary), CSRF 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_renders_primary_form_with_csrf() throws Exception {
        // stub 이미지는 primary=true이므로 대표 지정 폼은 미노출 (th:unless 조건)
        // primary=false인 이미지를 추가해서 테스트
        SellerProductRef productRef = new SellerProductRef(PRODUCT_ID, "테스트 상품");
        List<ProductImageResponse> images = List.of(
                new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/abc.jpg",
                        IMAGE_URL, 0, false)  // primary=false
        );
        when(sellerProductImageFacade.getManagementView(anyString(), anyBoolean(), anyLong()))
                .thenReturn(new ProductImageManagementView(productRef, images));

        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("대표 지정 폼 action이 .../primary 여야 함")
                .contains("/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID + "/primary");
        assertThat(body).as("_csrf 토큰이 대표 지정 폼에도 포함되어야 함").contains("_csrf");
    }

    // ============================================================
    // (I5) 정렬 변경 폼
    // ============================================================

    @Test
    @DisplayName("(I5) GET /images — 정렬 변경 폼: action(/order), sortOrder 필드, CSRF 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_renders_order_form_with_sort_order_field() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("정렬 변경 폼 action이 .../order 여야 함")
                .contains("/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID + "/order");
        assertThat(body).as("정렬 변경 폼에 sortOrder 필드가 있어야 함")
                .contains("name=\"sortOrder\"");
        assertThat(body).as("_csrf 토큰이 정렬 변경 폼에도 포함되어야 함").contains("_csrf");
    }

    // ============================================================
    // (I6) 삭제 폼
    // ============================================================

    @Test
    @DisplayName("(I6) GET /images — 삭제 폼: action(/delete), CSRF 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_renders_delete_form_with_csrf() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("삭제 폼 action이 .../delete 여야 함")
                .contains("/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID + "/delete");
        assertThat(body).as("_csrf 토큰이 삭제 폼에도 포함되어야 함").contains("_csrf");
    }

    // ============================================================
    // (I7) 이미지 목록 — imageUrl 표시
    // ============================================================

    @Test
    @DisplayName("(I7) GET /images — imageUrl이 img src로 렌더링되어야 함 (base URL 하드코딩 없이 DTO에서)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_renders_image_url_in_img_src() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("imageUrl이 img src로 렌더링되어야 함").contains(IMAGE_URL);
    }

    // ============================================================
    // (I8) 상품명 헤딩
    // ============================================================

    @Test
    @DisplayName("(I8) GET /images — 상품명 '테스트 상품'이 헤딩에 포함되어야 함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_renders_product_name_heading() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("상품명 '테스트 상품'이 헤딩에 포함되어야 함").contains("테스트 상품");
    }

    // ============================================================
    // (I9) 업로드 성공 redirect
    // ============================================================

    @Test
    @DisplayName("(I9) POST upload 성공 → 302 redirect:/seller/products/{productId}/images")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void upload_success_redirects() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", "fake-image-content".getBytes());

        when(sellerProductImageFacade.upload(anyString(), anyBoolean(), anyLong(),
                anyString(), anyString(), any()))
                .thenReturn(new ProductImageResponse(99L, PRODUCT_ID, "products/10/uuid.jpg",
                        "http://localhost/assets/products/10/uuid.jpg", 1, false));

        mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    // ============================================================
    // (I10) 빈 이미지 목록
    // ============================================================

    @Test
    @DisplayName("(I10) GET /images — 이미지 없을 때 '등록된 이미지가 없습니다.' 문구 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_renders_empty_message_when_no_images() throws Exception {
        SellerProductRef productRef = new SellerProductRef(PRODUCT_ID, "테스트 상품");
        when(sellerProductImageFacade.getManagementView(anyString(), anyBoolean(), anyLong()))
                .thenReturn(new ProductImageManagementView(productRef, List.of()));

        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("이미지 없을 때 빈 목록 메시지가 렌더링되어야 함")
                .contains("등록된 이미지가 없습니다.");
    }

    // ============================================================
    // (I11) nav SELLER 상품 등록 링크
    // ============================================================

    @Test
    @DisplayName("(I11) GET /images — nav에 상품 등록 링크 포함 (SELLER)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_nav_contains_seller_product_link() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /seller/products/new 링크가 있어야 함").contains("/seller/products/new");
    }

    // ============================================================
    // (I12) 이미지 관리 링크 — variants 화면으로 이동
    // ============================================================

    @Test
    @DisplayName("(I12) GET /images — variant 관리 링크 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void images_contains_variants_link() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("variant 관리 링크가 있어야 함")
                .contains("/seller/products/" + PRODUCT_ID + "/variants");
    }
}
