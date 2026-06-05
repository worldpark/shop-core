package com.shop.shop.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.dto.CategoryCreateRequest;
import com.shop.shop.product.dto.CategoryUpdateRequest;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.common.exception.DuplicateSlugException;
import com.shop.shop.common.exception.CategoryNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CategoryRestController + AdminCategoryRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - GET /api/v1/categories: 비인증 200(public)
 * - POST /api/v1/admin/categories: ADMIN 성공·SELLER 403·CONSUMER 403·비인증 401
 * - PATCH /api/v1/admin/categories/{id}: ADMIN 성공·SELLER 403·비인증 401
 * - slug 중복 409, parent 미존재 404
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class CategoryRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private MemberRepository memberRepository;

    @MockBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockBean
    private CategoryRepository categoryRepository;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private ProductOptionRepository productOptionRepository;

    @MockBean
    private OptionValueRepository optionValueRepository;

    @MockBean
    private ProductVariantRepository productVariantRepository;

    private String adminToken;
    private String sellerToken;
    private String consumerToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        // 기본 stub: 카테고리 목록 빈 리스트
        when(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    }

    // ============================================================
    // GET /api/v1/categories — 공개
    // ============================================================

    @Test
    @DisplayName("GET /api/v1/categories — 비인증 → 200(public, permitAll)")
    void getCategories_unauthenticated_returns_200() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/categories — SELLER 인증 → 200")
    void getCategories_seller_returns_200() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/categories — 빈 목록 반환")
    void getCategories_returns_empty_list() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ============================================================
    // POST /api/v1/admin/categories
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/admin/categories — ADMIN → 200 CategoryResponse")
    void createCategory_admin_returns_200() throws Exception {
        Category saved = categoryWithId(1L, "전자", "electronics");
        when(categoryRepository.existsBySlug("electronics")).thenReturn(false);
        when(categoryRepository.save(any())).thenReturn(saved);

        CategoryCreateRequest req = new CategoryCreateRequest("전자", "electronics", null, 0);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(1L))
                .andExpect(jsonPath("$.slug").value("electronics"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/categories — SELLER → 403")
    void createCategory_seller_returns_403() throws Exception {
        CategoryCreateRequest req = new CategoryCreateRequest("전자", "electronics", null, 0);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST /api/v1/admin/categories — CONSUMER → 403")
    void createCategory_consumer_returns_403() throws Exception {
        CategoryCreateRequest req = new CategoryCreateRequest("전자", "electronics", null, 0);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST /api/v1/admin/categories — 비인증 → 401")
    void createCategory_unauthenticated_returns_401() throws Exception {
        CategoryCreateRequest req = new CategoryCreateRequest("전자", "electronics", null, 0);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST /api/v1/admin/categories — slug 중복 → 409")
    void createCategory_duplicate_slug_returns_409() throws Exception {
        when(categoryRepository.existsBySlug("electronics")).thenReturn(true);

        CategoryCreateRequest req = new CategoryCreateRequest("전자", "electronics", null, 0);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/admin/categories — parent 미존재 → 404")
    void createCategory_parent_not_found_returns_404() throws Exception {
        when(categoryRepository.existsBySlug("mobile")).thenReturn(false);
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        CategoryCreateRequest req = new CategoryCreateRequest("모바일", "mobile", 999L, 0);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // PATCH /api/v1/admin/categories/{id}
    // ============================================================

    @Test
    @DisplayName("PATCH /api/v1/admin/categories/{id} — ADMIN → 200")
    void updateCategory_admin_returns_200() throws Exception {
        Category existing = categoryWithId(1L, "전자", "electronics");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsBySlugAndIdNot("electronics", 1L)).thenReturn(false);

        CategoryUpdateRequest req = new CategoryUpdateRequest("전자(수정)", "electronics", null, 1);

        mockMvc.perform(patch("/api/v1/admin/categories/1")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/categories/{id} — SELLER → 403")
    void updateCategory_seller_returns_403() throws Exception {
        CategoryUpdateRequest req = new CategoryUpdateRequest("전자(수정)", "electronics", null, 1);

        mockMvc.perform(patch("/api/v1/admin/categories/1")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/categories/{id} — 비인증 → 401")
    void updateCategory_unauthenticated_returns_401() throws Exception {
        CategoryUpdateRequest req = new CategoryUpdateRequest("전자(수정)", "electronics", null, 1);

        mockMvc.perform(patch("/api/v1/admin/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
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
