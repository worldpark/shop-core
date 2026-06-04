package com.shop.shop.product.service;

import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.ProductCreateRequest;
import com.shop.shop.product.dto.ProductResponse;
import com.shop.shop.product.dto.ProductUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ProductServiceResponse 단위 테스트.
 * REST principal(long) 추출·isAdmin 판정 후 ProductService 위임, DTO 매핑 검증.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceResponseTest {

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductServiceResponse productServiceResponse;

    @Test
    @DisplayName("register — auth.getPrincipal()=userId 추출 → ProductService.register 위임 + ProductResponse 반환")
    void register_extracts_principal_and_returns_dto() {
        Authentication auth = mockAuth(7L, List.of("ROLE_SELLER"));
        Product product = sampleProduct(7L);
        when(productService.register(eq(7L), any(), any(), any(), any())).thenReturn(product);

        ProductCreateRequest req = new ProductCreateRequest(null, "상품A", "설명", new BigDecimal("10000"));
        ProductResponse result = productServiceResponse.register(auth, req);

        assertThat(result).isInstanceOf(ProductResponse.class);
        assertThat(result.productId()).isEqualTo(10L);
        assertThat(result.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(result.ownerId()).isEqualTo(7L);
        // Entity 미노출 단언
        assertThat(result).isNotInstanceOf(Product.class);
    }

    @Test
    @DisplayName("update — ROLE_SELLER: isAdmin=false → ProductService.update(actorId, false, ...) 위임")
    void update_seller_is_not_admin() {
        Authentication auth = mockAuth(7L, List.of("ROLE_SELLER"));
        Product product = sampleProduct(7L);
        when(productService.update(eq(7L), eq(false), eq(10L), any(), any(), any(), any(), any()))
                .thenReturn(product);

        ProductUpdateRequest req = new ProductUpdateRequest(null, "수정상품", null,
                new BigDecimal("5000"), ProductStatus.ON_SALE);
        ProductResponse result = productServiceResponse.update(auth, 10L, req);

        assertThat(result.productId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("update — ROLE_ADMIN: isAdmin=true → ProductService.update(actorId, true, ...) 위임")
    void update_admin_is_admin() {
        Authentication auth = mockAuth(1L, List.of("ROLE_ADMIN"));
        Product product = sampleProduct(99L);
        when(productService.update(eq(1L), eq(true), eq(10L), any(), any(), any(), any(), any()))
                .thenReturn(product);

        ProductUpdateRequest req = new ProductUpdateRequest(null, "ADMIN수정", null,
                new BigDecimal("5000"), ProductStatus.HIDDEN);
        ProductResponse result = productServiceResponse.update(auth, 10L, req);

        assertThat(result.ownerId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("register — 응답에 Entity 필드(category, owner Entity) 미노출 — record 필드만 노출")
    void register_response_does_not_expose_entity() {
        Authentication auth = mockAuth(7L, List.of("ROLE_SELLER"));
        Product product = sampleProduct(7L);
        when(productService.register(any(Long.class), any(), any(), any(), any())).thenReturn(product);

        ProductCreateRequest req = new ProductCreateRequest(null, "상품", null, new BigDecimal("1000"));
        ProductResponse result = productServiceResponse.register(auth, req);

        // ProductResponse는 record — category Entity 직접 노출 없이 categoryId로 평탄화
        assertThat(result).isInstanceOf(ProductResponse.class);
        // categoryId는 null(미분류)
        assertThat(result.categoryId()).isNull();
    }

    // ============================================================
    // helpers
    // ============================================================

    private Authentication mockAuth(long userId, List<String> roles) {
        return new Authentication() {
            @Override
            public Collection<? extends GrantedAuthority> getAuthorities() {
                return roles.stream().map(SimpleGrantedAuthority::new).toList();
            }

            @Override
            public Object getCredentials() { return null; }

            @Override
            public Object getDetails() { return null; }

            @Override
            public Object getPrincipal() { return userId; }

            @Override
            public boolean isAuthenticated() { return true; }

            @Override
            public void setAuthenticated(boolean isAuthenticated) {}

            @Override
            public String getName() { return "user@example.com"; }
        };
    }

    private Product sampleProduct(long ownerId) {
        Product product = Product.create(ownerId, null, "상품", "설명", new BigDecimal("10000"));
        try {
            var idField = Product.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(product, 10L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return product;
    }
}
