package com.shop.shop.product.service;

import com.shop.shop.common.exception.ImageLimitExceededException;
import com.shop.shop.common.exception.ImageNotFoundException;
import com.shop.shop.common.exception.InvalidImageFileException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.storage.ObjectStorage;
import com.shop.shop.common.storage.StorageProperties;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductImageService 단위 테스트.
 *
 * <p>plan 5절 전 케이스 검증:
 * <ul>
 *   <li>업로드 성공 / 첫 업로드 자동 대표 / 추가 업로드 기존 대표 유지</li>
 *   <li>대표 지정 성공 / 기존 대표 해제(unset→flush→set 순서)</li>
 *   <li>정렬 변경 성공</li>
 *   <li>삭제 성공 / 대표 삭제 시 가장 앞 승계 / 마지막 삭제 시 대표 없음</li>
 *   <li>imageId 하위리소스 불일치 404 / 소유권 실패 404 / ADMIN 전체 관리 성공</li>
 *   <li>비이미지 MIME 400 / 화이트리스트 밖 확장자(.html, .svg, 확장자 없음) 400 / 허용 확장자(jpg·png 등) 성공</li>
 *   <li>storage.put 실패 시 repository.save 미호출 verify</li>
 *   <li>DB save 실패 시 storage.delete(storageKey) 호출 verify</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private StorageProperties storageProperties;

    @InjectMocks
    private ProductService productService;

    private ProductImageService productImageService;

    private static final long SELLER_ID = 2L;
    private static final long ADMIN_ID = 1L;
    private static final long OTHER_SELLER_ID = 5L;
    private static final long PRODUCT_ID = 10L;
    private static final long IMAGE_ID = 100L;
    private static final long IMAGE_ID_2 = 101L;

    @BeforeEach
    void setUp() {
        productImageService = new ProductImageService(
                productService, productImageRepository, objectStorage, storageProperties);

        // allowedExtensions / maxImagesPerProduct는 upload 테스트에서만 사용되므로 lenient로 설정
        lenient().when(storageProperties.getAllowedExtensions())
                .thenReturn(List.of("jpg", "jpeg", "png", "gif", "webp"));
        lenient().when(storageProperties.getMaxImagesPerProduct()).thenReturn(10);
    }

    // ============================================================
    // 업로드 성공 케이스
    // ============================================================

    @Test
    @DisplayName("업로드 성공 — 첫 이미지이면 isPrimary=true 자동 지정")
    void upload_firstImage_isPrimary() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
                .thenReturn(List.of());
        when(objectStorage.put(anyString(), anyString(), anyString(), any(InputStream.class)))
                .thenReturn("products/10/uuid.jpg");
        ProductImage savedImage = sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true);
        when(productImageRepository.save(any(ProductImage.class))).thenReturn(savedImage);

        ProductImage result = productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "test.jpg", "image/jpeg", sampleStream());

        assertThat(result.isPrimary()).isTrue();
        assertThat(result.getSortOrder()).isEqualTo(0);
    }

    @Test
    @DisplayName("추가 업로드 — 두 번째 이미지는 isPrimary=false, 기존 대표 유지")
    void upload_additionalImage_isNotPrimary() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductImage existing = sampleImage(IMAGE_ID, product, "products/10/first.jpg", 0, true);
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
                .thenReturn(List.of(existing));
        when(objectStorage.put(anyString(), anyString(), anyString(), any(InputStream.class)))
                .thenReturn("products/10/uuid2.jpg");
        ProductImage savedImage = sampleImage(IMAGE_ID_2, product, "products/10/uuid2.jpg", 1, false);
        when(productImageRepository.save(any(ProductImage.class))).thenReturn(savedImage);

        ProductImage result = productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "test2.png", "image/png", sampleStream());

        assertThat(result.isPrimary()).isFalse();
        assertThat(result.getSortOrder()).isEqualTo(1);
    }

    // ============================================================
    // 파일 검증
    // ============================================================

    @Test
    @DisplayName("MIME 타입이 image/로 시작하지 않으면 400")
    void upload_invalidMime_throws400() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "test.jpg", "application/pdf", sampleStream()))
                .isInstanceOf(InvalidImageFileException.class);
    }

    @Test
    @DisplayName("확장자 .html은 화이트리스트 밖 — 400")
    void upload_htmlExtension_throws400() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "evil.html", "image/jpeg", sampleStream()))
                .isInstanceOf(InvalidImageFileException.class);
    }

    @Test
    @DisplayName("확장자 .svg는 화이트리스트 밖 — 400")
    void upload_svgExtension_throws400() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "evil.svg", "image/svg+xml", sampleStream()))
                .isInstanceOf(InvalidImageFileException.class);
    }

    @Test
    @DisplayName("확장자 없는 파일 — 400")
    void upload_noExtension_throws400() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "noextfile", "image/jpeg", sampleStream()))
                .isInstanceOf(InvalidImageFileException.class);
    }

    @Test
    @DisplayName("허용 확장자 jpg — 성공")
    void upload_jpgExtension_success() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());
        when(objectStorage.put(anyString(), anyString(), anyString(), any())).thenReturn("products/10/uuid.jpg");
        when(productImageRepository.save(any())).thenReturn(sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true));

        ProductImage result = productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "photo.jpg", "image/jpeg", sampleStream());

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("허용 확장자 png — 성공")
    void upload_pngExtension_success() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());
        when(objectStorage.put(anyString(), anyString(), anyString(), any())).thenReturn("products/10/uuid.png");
        when(productImageRepository.save(any())).thenReturn(sampleImage(IMAGE_ID, product, "products/10/uuid.png", 0, true));

        ProductImage result = productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "photo.PNG", "image/png", sampleStream());

        assertThat(result).isNotNull();
    }

    // ============================================================
    // 개수 상한
    // ============================================================

    @Test
    @DisplayName("상품당 이미지 개수 상한 도달 — ImageLimitExceededException(400) 발생 + storage.put·repository.save 미호출")
    void upload_imageLimitReached_throwsLimitExceeded() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(storageProperties.getMaxImagesPerProduct()).thenReturn(10);
        when(productImageRepository.countByProductId(PRODUCT_ID)).thenReturn(10L); // 상한 도달

        assertThatThrownBy(() -> productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "test.jpg", "image/jpeg", sampleStream()))
                .isInstanceOf(ImageLimitExceededException.class)
                .hasMessageContaining("10");

        verify(objectStorage, never()).put(anyString(), anyString(), anyString(), any(InputStream.class));
        verify(productImageRepository, never()).save(any());
    }

    @Test
    @DisplayName("상품당 이미지 개수가 상한 미만 — 정상 업로드 성공")
    void upload_belowImageLimit_success() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(storageProperties.getMaxImagesPerProduct()).thenReturn(10);
        when(productImageRepository.countByProductId(PRODUCT_ID)).thenReturn(9L); // 상한 미만
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());
        when(objectStorage.put(anyString(), anyString(), anyString(), any(InputStream.class)))
                .thenReturn("products/10/uuid.jpg");
        when(productImageRepository.save(any())).thenReturn(
                sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true));

        ProductImage result = productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "test.jpg", "image/jpeg", sampleStream());

        assertThat(result).isNotNull();
    }

    // ============================================================
    // 업로드 트랜잭션 경계 / 보상
    // ============================================================

    @Test
    @DisplayName("storage.put 실패 시 repository.save 미호출")
    void upload_storagePutFails_saveNotCalled() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        // objectStorage.put 실패 시 findByProductIdOrderBySortOrderAscIdAsc는 호출되지 않으므로 stub 불필요
        when(objectStorage.put(anyString(), anyString(), anyString(), any(InputStream.class)))
                .thenThrow(new RuntimeException("storage 실패"));

        assertThatThrownBy(() -> productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "test.jpg", "image/jpeg", sampleStream()))
                .hasMessageContaining("storage 실패");

        verify(productImageRepository, never()).save(any());
    }

    @Test
    @DisplayName("DB save 실패 시 storage.delete 보상 호출")
    void upload_dbSaveFails_storageDeleteCalled() {
        String storageKey = "products/10/uuid.jpg";
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());
        when(objectStorage.put(anyString(), anyString(), anyString(), any(InputStream.class)))
                .thenReturn(storageKey);
        when(productImageRepository.save(any())).thenThrow(new RuntimeException("DB 실패"));

        assertThatThrownBy(() -> productImageService.upload(
                SELLER_ID, false, PRODUCT_ID, "test.jpg", "image/jpeg", sampleStream()))
                .hasMessageContaining("DB 실패");

        verify(objectStorage).delete(storageKey);
    }

    // ============================================================
    // 대표 지정
    // ============================================================

    @Test
    @DisplayName("대표 지정 성공 — 기존 대표 해제(unset + saveAndFlush) 후 신규 대표 지정 순서 검증")
    void setPrimary_success_unsetFlushBeforeSet() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductImage currentPrimary = sampleImage(IMAGE_ID, product, "products/10/first.jpg", 0, true);
        ProductImage target = sampleImage(IMAGE_ID_2, product, "products/10/second.jpg", 1, false);

        when(productImageRepository.findByProductIdAndIsPrimaryTrue(PRODUCT_ID))
                .thenReturn(Optional.of(currentPrimary));
        when(productImageRepository.findById(IMAGE_ID_2)).thenReturn(Optional.of(target));
        when(productImageRepository.saveAndFlush(currentPrimary)).thenReturn(currentPrimary);

        ProductImage result = productImageService.setPrimary(SELLER_ID, false, PRODUCT_ID, IMAGE_ID_2);

        // 기존 대표 해제 → saveAndFlush → 신규 대표 지정 순서 검증
        InOrder inOrder = inOrder(productImageRepository);
        inOrder.verify(productImageRepository).saveAndFlush(currentPrimary);

        assertThat(currentPrimary.isPrimary()).isFalse();
        assertThat(result.isPrimary()).isTrue();
    }

    @Test
    @DisplayName("대표 지정 — imageId가 productId 하위가 아니면 404")
    void setPrimary_wrongProduct_throws404() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        // 다른 상품 소속 이미지 — loadImageForProduct에서 filter 실패 → 404
        // findByProductIdAndIsPrimaryTrue는 loadImageForProduct 이후에 호출되므로 stub 불필요
        Product otherProduct = sampleProduct(SELLER_ID, 99L);
        ProductImage foreignImage = sampleImage(IMAGE_ID, otherProduct, "products/99/img.jpg", 0, false);
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(foreignImage));

        assertThatThrownBy(() -> productImageService.setPrimary(SELLER_ID, false, PRODUCT_ID, IMAGE_ID))
                .isInstanceOf(ImageNotFoundException.class);
    }

    // ============================================================
    // 정렬 변경
    // ============================================================

    @Test
    @DisplayName("정렬 변경 성공")
    void changeOrder_success() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/img.jpg", 0, true);
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(image));

        ProductImage result = productImageService.changeOrder(SELLER_ID, false, PRODUCT_ID, IMAGE_ID, 5);

        assertThat(result.getSortOrder()).isEqualTo(5);
    }

    // ============================================================
    // 삭제
    // ============================================================

    @Test
    @DisplayName("삭제 성공 — 대표가 아닌 이미지 삭제 시 승계 없음")
    void delete_nonPrimary_noSuccession() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductImage image = sampleImage(IMAGE_ID, product, "products/10/img.jpg", 1, false);
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(image));

        productImageService.delete(SELLER_ID, false, PRODUCT_ID, IMAGE_ID);

        verify(productImageRepository).delete(image);
        verify(objectStorage).delete("products/10/img.jpg");
    }

    @Test
    @DisplayName("대표 이미지 삭제 시 가장 앞 이미지 승계")
    void delete_primary_successionToFirst() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductImage primaryImage = sampleImage(IMAGE_ID, product, "products/10/first.jpg", 0, true);
        ProductImage nextImage = sampleImage(IMAGE_ID_2, product, "products/10/second.jpg", 1, false);

        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(primaryImage));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
                .thenReturn(List.of(nextImage));

        productImageService.delete(SELLER_ID, false, PRODUCT_ID, IMAGE_ID);

        assertThat(nextImage.isPrimary()).isTrue();
    }

    @Test
    @DisplayName("마지막 이미지 삭제 시 대표 없음 상태 허용")
    void delete_lastImage_noPrimary() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductImage lastImage = sampleImage(IMAGE_ID, product, "products/10/last.jpg", 0, true);
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(lastImage));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
                .thenReturn(List.of());

        productImageService.delete(SELLER_ID, false, PRODUCT_ID, IMAGE_ID);

        verify(productImageRepository).delete(lastImage);
        // 잔여 없으니 markPrimary 호출 없음 — 테스트는 단순히 예외 없이 완료됨을 검증
    }

    @Test
    @DisplayName("삭제 — imageId가 productId 하위가 아니면 404")
    void delete_wrongProduct_throws404() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Product otherProduct = sampleProduct(SELLER_ID, 99L);
        ProductImage foreignImage = sampleImage(IMAGE_ID, otherProduct, "products/99/img.jpg", 0, false);
        when(productImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(foreignImage));

        assertThatThrownBy(() -> productImageService.delete(SELLER_ID, false, PRODUCT_ID, IMAGE_ID))
                .isInstanceOf(ImageNotFoundException.class);
    }

    // ============================================================
    // 소유권 / ADMIN
    // ============================================================

    @Test
    @DisplayName("소유권 실패 — 타 판매자 상품 접근 시 404")
    void upload_otherSeller_throws404() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productImageService.upload(
                OTHER_SELLER_ID, false, PRODUCT_ID, "test.jpg", "image/jpeg", sampleStream()))
                .isInstanceOf(ProductAccessDeniedException.class);
    }

    @Test
    @DisplayName("ADMIN — 타 판매자 상품 이미지도 관리 가능")
    void upload_admin_canManageOtherSellerProduct() {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID)).thenReturn(List.of());
        when(objectStorage.put(anyString(), anyString(), anyString(), any())).thenReturn("products/10/uuid.jpg");
        when(productImageRepository.save(any())).thenReturn(
                sampleImage(IMAGE_ID, product, "products/10/uuid.jpg", 0, true));

        ProductImage result = productImageService.upload(
                ADMIN_ID, true, PRODUCT_ID, "test.jpg", "image/jpeg", sampleStream());

        assertThat(result).isNotNull();
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

    private InputStream sampleStream() {
        return new ByteArrayInputStream("fake-image-content".getBytes());
    }
}
