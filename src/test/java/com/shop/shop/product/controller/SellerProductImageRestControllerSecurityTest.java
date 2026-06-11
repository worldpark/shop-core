package com.shop.shop.product.controller;

import com.shop.shop.common.exception.ImageNotFoundException;
import com.shop.shop.common.exception.InvalidImageFileException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.common.storage.StorageProperties;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
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
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SellerProductImageRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>010 패턴: @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles(test) + @MockitoBean Repository.
 *
 * <p>검증 시나리오:
 * - GET images: SELLER 200 / ADMIN 200 / CONSUMER 403 / 비인증 401
 * - POST multipart 업로드 성공 / 비이미지 400 / 검증 실패 400
 * - PATCH primary 성공
 * - PATCH order 성공
 * - DELETE 성공
 * - 타 판매자 404
 * - 응답에 로컬 절대경로 미포함 단언
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductImageRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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
    private ShipmentRepository shipmentRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @Autowired
    private AssetUrlResolver assetUrlResolver;

    @Autowired
    private StorageProperties storageProperties;

    private String adminToken;
    private String sellerToken;
    private String sellerToken2;
    private String consumerToken;

    private static final long SELLER_ID = 2L;
    private static final long SELLER2_ID = 5L;
    private static final long ADMIN_ID = 1L;
    private static final long PRODUCT_ID = 10L;
    private static final long IMAGE_ID = 100L;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(ADMIN_ID, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(SELLER_ID, "seller@example.com", List.of("ROLE_SELLER"));
        sellerToken2 = jwtTokenProvider.createAccess(SELLER2_ID, "seller2@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
    }

    // ============================================================
    // GET /api/v1/seller/products/{id}/images — 권한 매트릭스
    // ============================================================

    @Test
    @DisplayName("GET images — SELLER → 200")
    void listImages_seller_returns200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/images")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET images — ADMIN → 200 (RoleHierarchy 함의)")
    void listImages_admin_returns200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/images")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET images — CONSUMER → 403")
    void listImages_consumer_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/images")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET images — 비인증 → 401")
    void listImages_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/images"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET images — 타 판매자 상품 → 404")
    void listImages_otherSeller_returns404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/images")
                        .header("Authorization", "Bearer " + sellerToken2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // POST /api/v1/seller/products/{id}/images — 업로드
    // ============================================================

    @Test
    @DisplayName("POST images — multipart 업로드 성공 → 200 ProductImageResponse (절대경로 미포함)")
    void uploadImage_seller_returns200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage savedImage = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());
        when(productImageRepository.save(any())).thenReturn(savedImage);

        // LocalObjectStorage를 직접 쓰므로 실제 파일 저장 시도가 일어남.
        // 테스트에서는 storage mock 없이 실제 임시 디렉터리에 저장.
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", "fake-image".getBytes());

        String body = mockMvc.perform(multipart("/api/v1/seller/products/" + PRODUCT_ID + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(IMAGE_ID))
                .andExpect(jsonPath("$.storageKey").isString())
                .andExpect(jsonPath("$.imageUrl").isString())
                .andReturn().getResponse().getContentAsString();

        // 절대경로 미포함 단언 (로컬 파일 경로 노출 없음)
        assertThat(body).doesNotContain("C:\\");
        assertThat(body).doesNotContain("/tmp/");
        assertThat(body).doesNotContain("uploads/");
    }

    @Test
    @DisplayName("POST images — 비이미지 MIME → 400 InvalidImageFileException")
    void uploadImage_nonImageMime_returns400() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "fake-pdf".getBytes());

        mockMvc.perform(multipart("/api/v1/seller/products/" + PRODUCT_ID + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST images — 확장자 svg → 400")
    void uploadImage_svgExtension_returns400() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.svg", "image/svg+xml", "<svg></svg>".getBytes());

        mockMvc.perform(multipart("/api/v1/seller/products/" + PRODUCT_ID + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ============================================================
    // PATCH primary
    // ============================================================

    @Test
    @DisplayName("PATCH primary — 성공 → 200")
    void setPrimary_seller_returns200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, false);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdAndIsPrimaryTrue(PRODUCT_ID)).thenReturn(Optional.empty());
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(image));

        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID + "/primary")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(IMAGE_ID));
    }

    @Test
    @DisplayName("PATCH primary — 타 판매자 → 404")
    void setPrimary_otherSeller_returns404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID + "/primary")
                        .header("Authorization", "Bearer " + sellerToken2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // PATCH order
    // ============================================================

    @Test
    @DisplayName("PATCH order — 성공 → 200")
    void changeOrder_seller_returns200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 3, false);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(image));

        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID + "/order")
                        .param("sortOrder", "3")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(IMAGE_ID))
                .andExpect(jsonPath("$.sortOrder").value(3));
    }

    // ============================================================
    // DELETE
    // ============================================================

    @Test
    @DisplayName("DELETE image — 성공 → 204")
    void deleteImage_seller_returns204() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, false);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(image));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());

        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE image — imageId 미존재 → 404")
    void deleteImage_notFound_returns404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/images/" + IMAGE_ID)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Product sampleProduct(long ownerId, long productId) {
        Product product = Product.create(ownerId, null, "테스트 상품", "설명", new BigDecimal("10000"));
        try {
            var idField = Product.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(product, productId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return product;
    }

    private ProductImage sampleImage(long imageId, Product product, String storageKey, int sortOrder, boolean isPrimary) {
        ProductImage image = ProductImage.create(product, storageKey, sortOrder, isPrimary);
        try {
            var idField = ProductImage.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(image, imageId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return image;
    }
}
