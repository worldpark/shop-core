package com.shop.shop.web.product;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.ImageNotFoundException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
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
import com.shop.shop.payment.repository.PaymentRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * SellerProductImageViewController + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>SellerProductImageFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>DB/JPA/Flyway/Kafka 자동설정 제외 프로파일(test)로 기동.
 * FakeRefreshTokenStore: Redis 미기동 비파괴.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET SELLER → 200, view name seller/product-images, 모델 키 3종(product/images/imageUploadForm)</li>
 *   <li>GET CONSUMER → 403, 비인증 → redirect(/login), ADMIN → 200</li>
 *   <li>POST 업로드 성공 → 302 redirect + flashSuccess</li>
 *   <li>POST 업로드 파일 미선택(@Valid 실패) → flashError redirect</li>
 *   <li>POST 업로드 BusinessException → flashError redirect</li>
 *   <li>POST 대표 지정 성공 → 302 redirect + flashSuccess + facade 호출 verify</li>
 *   <li>POST 대표 지정 BusinessException → flashError redirect</li>
 *   <li>POST 정렬 변경 성공 → 302 redirect + flashSuccess + facade 호출 verify</li>
 *   <li>POST 정렬 변경 BusinessException → flashError redirect</li>
 *   <li>POST 삭제 성공 → 302 redirect + flashSuccess + facade 호출 verify</li>
 *   <li>POST 삭제 BusinessException → flashError redirect</li>
 *   <li>GET 타인 상품 → 404(ProductAccessDeniedException)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductImageViewControllerTest {

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
    private PaymentRepository paymentRepository;

    /**
     * SellerProductImageFacade를 @MockitoBean으로 대체 — product.spi facade 격리.
     */
    @MockitoBean
    private SellerProductImageFacade sellerProductImageFacade;

    private static final long PRODUCT_ID = 10L;
    private static final long IMAGE_ID = 50L;
    private static final String SELLER_EMAIL = "seller@example.com";
    private static final String BASE_URL = "/seller/products/" + PRODUCT_ID + "/images";

    private ProductImageManagementView stubView;

    @BeforeEach
    void setUp() {
        SellerProductRef productRef = new SellerProductRef(PRODUCT_ID, "테스트 상품");
        List<ProductImageResponse> images = List.of(
                new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/abc.jpg",
                        "http://localhost/assets/products/10/abc.jpg", 0, true)
        );
        stubView = new ProductImageManagementView(productRef, images);

        when(sellerProductImageFacade.getManagementView(anyString(), anyBoolean(), anyLong()))
                .thenReturn(stubView);
    }

    // ============================================================
    // GET /seller/products/{productId}/images
    // ============================================================

    @Test
    @DisplayName("GET images — SELLER → 200, view seller/product-images")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void get_seller_returns_200_with_correct_view() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-images"));
    }

    @Test
    @DisplayName("GET images — SELLER → 모델 키 3종(product/images/imageUploadForm) 존재")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void get_seller_model_contains_required_attributes() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("product"))
                .andExpect(model().attributeExists("images"))
                .andExpect(model().attributeExists("imageUploadForm"));
    }

    @Test
    @DisplayName("GET images — CONSUMER → 403")
    @WithMockUser(username = "consumer@example.com", roles = "CONSUMER")
    void get_consumer_returns_403() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET images — 비인증 → /login redirect(302)")
    void get_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    @Test
    @DisplayName("GET images — ADMIN → 200(RoleHierarchy 함의)")
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void get_admin_returns_200() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-images"));
    }

    @Test
    @DisplayName("GET images — 타인 상품 → 404(ProductAccessDeniedException)")
    @WithMockUser(username = "other@example.com", roles = "SELLER")
    void get_other_seller_product_returns_404() throws Exception {
        when(sellerProductImageFacade.getManagementView(eq("other@example.com"), eq(false), eq(PRODUCT_ID)))
                .thenThrow(new ProductAccessDeniedException(PRODUCT_ID));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // POST /seller/products/{productId}/images — 업로드
    // ============================================================

    @Test
    @DisplayName("POST upload — 성공(CSRF, multipart) → 302 redirect:/seller/products/{productId}/images")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void upload_success_redirects() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", "fake-image-content".getBytes());

        when(sellerProductImageFacade.upload(anyString(), anyBoolean(), anyLong(),
                anyString(), anyString(), any()))
                .thenReturn(new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/uuid.jpg",
                        "http://localhost/assets/products/10/uuid.jpg", 0, true));

        mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    @Test
    @DisplayName("POST upload — 성공 → flashSuccess 설정")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void upload_success_sets_flash_success() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", "fake-image-content".getBytes());

        when(sellerProductImageFacade.upload(anyString(), anyBoolean(), anyLong(),
                anyString(), anyString(), any()))
                .thenReturn(new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/uuid.jpg",
                        "http://localhost/assets/products/10/uuid.jpg", 0, true));

        mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @DisplayName("POST upload — 성공 → facade.upload 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void upload_success_calls_facade() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "product.jpg", "image/jpeg", "fake-image-content".getBytes());

        when(sellerProductImageFacade.upload(anyString(), anyBoolean(), anyLong(),
                anyString(), anyString(), any()))
                .thenReturn(new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/uuid.jpg",
                        "http://localhost/assets/products/10/uuid.jpg", 0, true));

        mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductImageFacade).upload(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID),
                eq("product.jpg"), eq("image/jpeg"), any());
    }

    @Test
    @DisplayName("POST upload — 빈 파일(isEmpty=true) → flashError redirect, facade.upload 미호출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void upload_empty_file_redirects_with_flash_error_and_no_facade_call() throws Exception {
        // 빈 multipart 파일 전송 (파일 미선택 시나리오: originalFilename="", content 없음)
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "", "application/octet-stream", new byte[0]);

        mockMvc.perform(multipart(BASE_URL)
                        .file(emptyFile)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashError"));

        // 빈 파일이면 facade.upload가 호출되어서는 안 된다
        verify(sellerProductImageFacade, never()).upload(
                anyString(), anyBoolean(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("POST upload — BusinessException(비이미지 파일) → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void upload_business_exception_redirects_with_flash_error() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream", "fake".getBytes());

        when(sellerProductImageFacade.upload(anyString(), anyBoolean(), anyLong(),
                anyString(), anyString(), any()))
                .thenThrow(new BusinessException("허용되지 않는 파일 형식입니다."));

        mockMvc.perform(multipart(BASE_URL)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // POST /seller/products/{productId}/images/{imageId}/primary — 대표 지정
    // ============================================================

    @Test
    @DisplayName("POST /{imageId}/primary — 성공 → 302 redirect + flashSuccess")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void setPrimary_success_redirects_with_flash_success() throws Exception {
        when(sellerProductImageFacade.setPrimary(anyString(), anyBoolean(), anyLong(), anyLong()))
                .thenReturn(new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/abc.jpg",
                        "http://localhost/assets/products/10/abc.jpg", 0, true));

        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/primary")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @DisplayName("POST /{imageId}/primary — 성공 → facade.setPrimary 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void setPrimary_success_calls_facade() throws Exception {
        when(sellerProductImageFacade.setPrimary(anyString(), anyBoolean(), anyLong(), anyLong()))
                .thenReturn(new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/abc.jpg",
                        "http://localhost/assets/products/10/abc.jpg", 0, true));

        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/primary")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductImageFacade).setPrimary(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID), eq(IMAGE_ID));
    }

    @Test
    @DisplayName("POST /{imageId}/primary — BusinessException → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void setPrimary_business_exception_redirects_with_flash_error() throws Exception {
        when(sellerProductImageFacade.setPrimary(anyString(), anyBoolean(), anyLong(), anyLong()))
                .thenThrow(new ImageNotFoundException(IMAGE_ID));

        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/primary")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // POST /seller/products/{productId}/images/{imageId}/order — 정렬 변경
    // ============================================================

    @Test
    @DisplayName("POST /{imageId}/order — 성공 → 302 redirect + flashSuccess")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void changeOrder_success_redirects_with_flash_success() throws Exception {
        when(sellerProductImageFacade.changeOrder(anyString(), anyBoolean(), anyLong(), anyLong(), anyInt()))
                .thenReturn(new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/abc.jpg",
                        "http://localhost/assets/products/10/abc.jpg", 2, true));

        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/order")
                        .with(csrf())
                        .param("sortOrder", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @DisplayName("POST /{imageId}/order — 성공 → facade.changeOrder 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void changeOrder_success_calls_facade() throws Exception {
        when(sellerProductImageFacade.changeOrder(anyString(), anyBoolean(), anyLong(), anyLong(), anyInt()))
                .thenReturn(new ProductImageResponse(IMAGE_ID, PRODUCT_ID, "products/10/abc.jpg",
                        "http://localhost/assets/products/10/abc.jpg", 2, true));

        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/order")
                        .with(csrf())
                        .param("sortOrder", "2"))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductImageFacade).changeOrder(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID), eq(IMAGE_ID), eq(2));
    }

    @Test
    @DisplayName("POST /{imageId}/order — BusinessException → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void changeOrder_business_exception_redirects_with_flash_error() throws Exception {
        when(sellerProductImageFacade.changeOrder(anyString(), anyBoolean(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new ImageNotFoundException(IMAGE_ID));

        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/order")
                        .with(csrf())
                        .param("sortOrder", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // POST /seller/products/{productId}/images/{imageId}/delete — 삭제
    // ============================================================

    @Test
    @DisplayName("POST /{imageId}/delete — 성공 → 302 redirect + flashSuccess")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void delete_success_redirects_with_flash_success() throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @DisplayName("POST /{imageId}/delete — 성공 → facade.delete 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void delete_success_calls_facade() throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductImageFacade).delete(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID), eq(IMAGE_ID));
    }

    @Test
    @DisplayName("POST /{imageId}/delete — BusinessException → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void delete_business_exception_redirects_with_flash_error() throws Exception {
        doThrow(new ImageNotFoundException(IMAGE_ID))
                .when(sellerProductImageFacade)
                .delete(anyString(), anyBoolean(), anyLong(), anyLong());

        mockMvc.perform(post(BASE_URL + "/" + IMAGE_ID + "/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL))
                .andExpect(flash().attributeExists("flashError"));
    }
}
