package com.shop.shop.product.service;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.dto.ProductImageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductImageServiceResponse 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>auth.getPrincipal() long 추출 + isAdmin 판정 정확성</li>
 *   <li>ProductImageService에 올바른 actorId/isAdmin 전달 검증</li>
 *   <li>AssetUrlResolver를 통한 imageUrl 합성 검증</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductImageServiceResponseTest {

    @Mock
    private ProductImageService productImageService;

    @Mock
    private AssetUrlResolver assetUrlResolver;

    @InjectMocks
    private ProductImageServiceResponse productImageServiceResponse;

    private static final long SELLER_ID = 2L;
    private static final long ADMIN_ID = 1L;
    private static final long PRODUCT_ID = 10L;
    private static final long IMAGE_ID = 100L;

    @Test
    @DisplayName("listImages — SELLER principal long 추출, isAdmin=false, assetUrlResolver 위임")
    void listImages_sellerPrincipal_extracted() {
        Authentication auth = sellerAuth(SELLER_ID);
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);

        when(productImageService.listImages(eq(SELLER_ID), eq(false), eq(PRODUCT_ID)))
                .thenReturn(List.of(image));
        when(assetUrlResolver.toUrl("products/10/uuid.jpg")).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        List<ProductImageResponse> result = productImageServiceResponse.listImages(auth, PRODUCT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).imageUrl()).isEqualTo("http://localhost:8080/assets/products/10/uuid.jpg");
        verify(productImageService).listImages(SELLER_ID, false, PRODUCT_ID);
    }

    @Test
    @DisplayName("listImages — ADMIN principal은 isAdmin=true 전달")
    void listImages_adminPrincipal_isAdminTrue() {
        Authentication auth = adminAuth(ADMIN_ID);
        when(productImageService.listImages(eq(ADMIN_ID), eq(true), eq(PRODUCT_ID))).thenReturn(List.of());

        productImageServiceResponse.listImages(auth, PRODUCT_ID);

        verify(productImageService).listImages(ADMIN_ID, true, PRODUCT_ID);
    }

    @Test
    @DisplayName("upload — auth principal/isAdmin 추출 후 service에 위임")
    void upload_delegatesToService() {
        Authentication auth = sellerAuth(SELLER_ID);
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);
        InputStream is = new ByteArrayInputStream("data".getBytes());

        when(productImageService.upload(eq(SELLER_ID), eq(false), eq(PRODUCT_ID), anyString(), anyString(), any()))
                .thenReturn(image);
        when(assetUrlResolver.toUrl(anyString())).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        ProductImageResponse result = productImageServiceResponse.upload(auth, PRODUCT_ID, "test.jpg", "image/jpeg", is);

        assertThat(result.imageId()).isEqualTo(IMAGE_ID);
        assertThat(result.imageUrl()).contains("assets");
    }

    @Test
    @DisplayName("setPrimary — auth principal/isAdmin 추출 후 service에 위임")
    void setPrimary_delegatesToService() {
        Authentication auth = sellerAuth(SELLER_ID);
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);

        when(productImageService.setPrimary(eq(SELLER_ID), eq(false), eq(PRODUCT_ID), eq(IMAGE_ID))).thenReturn(image);
        when(assetUrlResolver.toUrl(anyString())).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        ProductImageResponse result = productImageServiceResponse.setPrimary(auth, PRODUCT_ID, IMAGE_ID);

        assertThat(result.primary()).isTrue();
        verify(productImageService).setPrimary(SELLER_ID, false, PRODUCT_ID, IMAGE_ID);
    }

    @Test
    @DisplayName("changeOrder — service에 sortOrder 전달")
    void changeOrder_delegatesToService() {
        Authentication auth = sellerAuth(SELLER_ID);
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 5, false);

        when(productImageService.changeOrder(eq(SELLER_ID), eq(false), eq(PRODUCT_ID), eq(IMAGE_ID), eq(5))).thenReturn(image);
        when(assetUrlResolver.toUrl(anyString())).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        ProductImageResponse result = productImageServiceResponse.changeOrder(auth, PRODUCT_ID, IMAGE_ID, 5);

        assertThat(result.sortOrder()).isEqualTo(5);
        verify(productImageService).changeOrder(SELLER_ID, false, PRODUCT_ID, IMAGE_ID, 5);
    }

    @Test
    @DisplayName("delete — service에 위임")
    void delete_delegatesToService() {
        Authentication auth = sellerAuth(SELLER_ID);

        productImageServiceResponse.delete(auth, PRODUCT_ID, IMAGE_ID);

        verify(productImageService).delete(SELLER_ID, false, PRODUCT_ID, IMAGE_ID);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Authentication sellerAuth(long userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userId);
        when(auth.getAuthorities()).thenReturn(
                (Collection) List.of((GrantedAuthority) () -> "ROLE_SELLER"));
        return auth;
    }

    private Authentication adminAuth(long userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userId);
        when(auth.getAuthorities()).thenReturn(
                (Collection) List.of((GrantedAuthority) () -> "ROLE_ADMIN"));
        return auth;
    }

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
