package com.shop.shop.product.service;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.dto.ProductImageManagementView;
import com.shop.shop.product.dto.ProductImageResponse;
import com.shop.shop.product.spi.SellerProductImageFacade;
import com.shop.shop.product.spi.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SellerProductImageFacadeImpl 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>email → userId 변환 후 서비스에 전달</li>
 *   <li>Entity → DTO 변환</li>
 *   <li>getManagementView 집계 조합</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerProductImageFacadeImplTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProductImageService productImageService;

    @Mock
    private AssetUrlResolver assetUrlResolver;

    @Mock
    private UserDirectory userDirectory;

    private SellerProductImageFacade facade;

    private static final String SELLER_EMAIL = "seller@example.com";
    private static final long SELLER_ID = 2L;
    private static final long PRODUCT_ID = 10L;
    private static final long IMAGE_ID = 100L;

    @BeforeEach
    void setUp() {
        facade = new SellerProductImageFacadeImpl(productService, productImageService, assetUrlResolver, userDirectory);
    }

    @Test
    @DisplayName("getManagementView — email→userId 변환 후 집계 반환")
    void getManagementView_emailConverted() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);

        when(userDirectory.findUserIdByEmail(SELLER_EMAIL)).thenReturn(SELLER_ID);
        when(productService.getOwnedProduct(eq(SELLER_ID), eq(false), eq(PRODUCT_ID))).thenReturn(product);
        when(productImageService.listImages(eq(SELLER_ID), eq(false), eq(PRODUCT_ID))).thenReturn(List.of(image));
        when(assetUrlResolver.toUrl("products/10/uuid.jpg")).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        ProductImageManagementView view = facade.getManagementView(SELLER_EMAIL, false, PRODUCT_ID);

        assertThat(view.product().productId()).isEqualTo(PRODUCT_ID);
        assertThat(view.images()).hasSize(1);
        assertThat(view.images().get(0).imageUrl()).contains("assets");
        verify(userDirectory).findUserIdByEmail(SELLER_EMAIL);
    }

    @Test
    @DisplayName("upload — email→userId 변환 후 서비스 위임, DTO 변환")
    void upload_emailConverted_dtoReturned() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);
        InputStream is = new ByteArrayInputStream("data".getBytes());

        when(userDirectory.findUserIdByEmail(SELLER_EMAIL)).thenReturn(SELLER_ID);
        when(productImageService.upload(eq(SELLER_ID), eq(false), eq(PRODUCT_ID), anyString(), anyString(), any()))
                .thenReturn(image);
        when(assetUrlResolver.toUrl("products/10/uuid.jpg")).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        ProductImageResponse result = facade.upload(SELLER_EMAIL, false, PRODUCT_ID, "test.jpg", "image/jpeg", is);

        assertThat(result.imageId()).isEqualTo(IMAGE_ID);
        assertThat(result.primary()).isTrue();
        verify(userDirectory).findUserIdByEmail(SELLER_EMAIL);
    }

    @Test
    @DisplayName("setPrimary — email→userId 변환 후 서비스 위임")
    void setPrimary_emailConverted() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);

        when(userDirectory.findUserIdByEmail(SELLER_EMAIL)).thenReturn(SELLER_ID);
        when(productImageService.setPrimary(eq(SELLER_ID), eq(false), eq(PRODUCT_ID), eq(IMAGE_ID))).thenReturn(image);
        when(assetUrlResolver.toUrl(anyString())).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        ProductImageResponse result = facade.setPrimary(SELLER_EMAIL, false, PRODUCT_ID, IMAGE_ID);

        assertThat(result.imageId()).isEqualTo(IMAGE_ID);
        verify(productImageService).setPrimary(SELLER_ID, false, PRODUCT_ID, IMAGE_ID);
    }

    @Test
    @DisplayName("changeOrder — email→userId 변환 후 서비스 위임, sortOrder 전달")
    void changeOrder_emailConverted_sortOrderPassed() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 3, false);

        when(userDirectory.findUserIdByEmail(SELLER_EMAIL)).thenReturn(SELLER_ID);
        when(productImageService.changeOrder(eq(SELLER_ID), eq(false), eq(PRODUCT_ID), eq(IMAGE_ID), eq(3)))
                .thenReturn(image);
        when(assetUrlResolver.toUrl(anyString())).thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");

        ProductImageResponse result = facade.changeOrder(SELLER_EMAIL, false, PRODUCT_ID, IMAGE_ID, 3);

        assertThat(result.sortOrder()).isEqualTo(3);
        verify(productImageService).changeOrder(SELLER_ID, false, PRODUCT_ID, IMAGE_ID, 3);
    }

    @Test
    @DisplayName("delete — email→userId 변환 후 서비스 위임")
    void delete_emailConverted() {
        when(userDirectory.findUserIdByEmail(SELLER_EMAIL)).thenReturn(SELLER_ID);

        facade.delete(SELLER_EMAIL, false, PRODUCT_ID, IMAGE_ID);

        verify(productImageService).delete(SELLER_ID, false, PRODUCT_ID, IMAGE_ID);
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
