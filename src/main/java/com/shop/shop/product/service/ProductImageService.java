package com.shop.shop.product.service;

import com.shop.shop.common.exception.ImageLimitExceededException;
import com.shop.shop.common.exception.ImageNotFoundException;
import com.shop.shop.common.exception.InvalidImageFileException;
import com.shop.shop.common.storage.ObjectStorage;
import com.shop.shop.common.storage.StorageProperties;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.repository.ProductImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * 상품 이미지 도메인 서비스.
 *
 * <p>이미지 업로드·대표 지정·정렬 변경·삭제의 도메인 로직을 단일 소유한다.
 * 소유권 검사는 {@link ProductService#getOwnedProduct}에 위임한다.
 *
 * <p>업로드 트랜잭션 경계 / 보상:
 * <ul>
 *   <li>storage.put 먼저 → DB 저장: put 실패 시 DB 잔여 없음</li>
 *   <li>DB 저장 실패 시 storage.delete로 보상 삭제 (try/catch 격리 — 보상 실패가 원본 예외를 가리지 않음)</li>
 * </ul>
 *
 * <p>대표 이미지 불변식:
 * <ul>
 *   <li>대표 전이: 기존 대표 unmarkPrimary → saveAndFlush → 신규 markPrimary (partial unique index 충돌 회피)</li>
 *   <li>첫 업로드 시 자동 대표 지정</li>
 *   <li>대표 삭제 시 잔여 이미지가 있으면 가장 앞(sortOrder ASC, id ASC) 이미지를 승계</li>
 * </ul>
 *
 * <p>파일 검증 (plan 1.6):
 * <ul>
 *   <li>Content-Type이 image/로 시작해야 한다</li>
 *   <li>확장자가 allowedExtensions 화이트리스트에 있어야 한다</li>
 * </ul>
 *
 * <p>레이어: *Controller → ProductImageServiceResponse(REST) / Facade(View) → ProductImageService → *Repository
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductImageService {

    private final ProductService productService;
    private final ProductImageRepository productImageRepository;
    private final ObjectStorage objectStorage;
    private final StorageProperties storageProperties;

    /**
     * 상품 이미지 목록 조회.
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @return sortOrder ASC, id ASC 정렬된 이미지 목록
     */
    @Transactional(readOnly = true)
    public List<ProductImage> listImages(long actorId, boolean actorIsAdmin, long productId) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);
        return productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
    }

    /**
     * 이미지 업로드.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>소유권 검사</li>
     *   <li>파일 검증 (MIME/확장자 화이트리스트) — storage.put 이전</li>
     *   <li>storage.put → storageKey 획득</li>
     *   <li>DB 저장 (첫 이미지면 isPrimary=true)</li>
     *   <li>DB 저장 실패 시 storage.delete 보상</li>
     * </ol>
     *
     * @param actorId          행위자 userId
     * @param actorIsAdmin     행위자 ADMIN 여부
     * @param productId        대상 상품 ID
     * @param originalFilename 원본 파일명 (확장자 검증용)
     * @param contentType      MIME 타입 (검증용)
     * @param inputStream      파일 데이터 스트림
     * @return 저장된 ProductImage Entity
     * @throws InvalidImageFileException   MIME/확장자 검증 실패 시
     * @throws ImageLimitExceededException 상품당 이미지 개수 상한 초과 시
     */
    public ProductImage upload(long actorId, boolean actorIsAdmin, long productId,
                                String originalFilename, String contentType, InputStream inputStream) {
        Product product = productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        validateImageFile(originalFilename, contentType);
        validateImageCount(productId);

        String keyPrefix = "products/" + productId;
        String storageKey = objectStorage.put(keyPrefix, originalFilename, contentType, inputStream);

        try {
            List<ProductImage> existing = productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
            int sortOrder = existing.size();
            boolean isPrimary = existing.isEmpty();

            ProductImage image = ProductImage.create(product, storageKey, sortOrder, isPrimary);
            return productImageRepository.save(image);
        } catch (Exception e) {
            // DB 저장 실패 시 storage 보상 삭제 (보상 실패가 원본 예외를 가리지 않도록 격리)
            try {
                objectStorage.delete(storageKey);
            } catch (Exception compensation) {
                log.warn("DB 저장 실패 후 storage 보상 삭제 실패 (고아 파일 발생 가능): storageKey={}", storageKey, compensation);
            }
            throw e;
        }
    }

    /**
     * 대표 이미지 지정.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>소유권 검사</li>
     *   <li>하위리소스 검증</li>
     *   <li>기존 대표 해제 → saveAndFlush (partial unique index 충돌 회피)</li>
     *   <li>대상 대표 지정</li>
     * </ol>
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param imageId      대표로 지정할 이미지 ID
     * @return 대표로 지정된 ProductImage Entity
     * @throws ImageNotFoundException 하위리소스 불일치 또는 미존재 시
     */
    public ProductImage setPrimary(long actorId, boolean actorIsAdmin, long productId, long imageId) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        ProductImage target = loadImageForProduct(imageId, productId);

        // 기존 대표 해제 → saveAndFlush (partial unique index 충돌 회피)
        productImageRepository.findByProductIdAndIsPrimaryTrue(productId)
                .ifPresent(existing -> {
                    existing.unmarkPrimary();
                    productImageRepository.saveAndFlush(existing);
                });

        target.markPrimary();
        return target;
    }

    /**
     * 정렬 순서 변경.
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param imageId      변경할 이미지 ID
     * @param sortOrder    새 정렬 순서
     * @return 변경된 ProductImage Entity
     * @throws ImageNotFoundException 하위리소스 불일치 또는 미존재 시
     */
    public ProductImage changeOrder(long actorId, boolean actorIsAdmin, long productId, long imageId, int sortOrder) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        ProductImage image = loadImageForProduct(imageId, productId);
        image.changeSortOrder(sortOrder);
        return image;
    }

    /**
     * 이미지 삭제.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>소유권 검사</li>
     *   <li>하위리소스 검증</li>
     *   <li>대표 여부·storageKey 보관</li>
     *   <li>DB에서 이미지 삭제</li>
     *   <li>대표였고 잔여 이미지 존재 시 가장 앞 이미지를 대표로 승계</li>
     *   <li>storage 파일 보상 삭제 (try/catch — 파일 정리 실패가 트랜잭션을 깨지 않도록 로그만)</li>
     * </ol>
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param imageId      삭제할 이미지 ID
     * @throws ImageNotFoundException 하위리소스 불일치 또는 미존재 시
     */
    public void delete(long actorId, boolean actorIsAdmin, long productId, long imageId) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        ProductImage image = loadImageForProduct(imageId, productId);
        boolean wasPrimary = image.isPrimary();
        String storageKey = image.getStorageKey();

        productImageRepository.delete(image);
        productImageRepository.flush();

        // 대표였고 잔여 이미지 존재 시 가장 앞 이미지 승계
        if (wasPrimary) {
            List<ProductImage> remaining = productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
            if (!remaining.isEmpty()) {
                remaining.get(0).markPrimary();
            }
        }

        // storage 파일 삭제 (보상 — 실패 시 트랜잭션 불파괴)
        try {
            objectStorage.delete(storageKey);
        } catch (Exception e) {
            log.warn("이미지 삭제 후 storage 파일 정리 실패 (고아 파일 발생 가능): storageKey={}", storageKey, e);
        }
    }

    // ============================================================
    // 내부 헬퍼
    // ============================================================

    /**
     * imageId로 이미지를 조회하고 productId 하위리소스인지 검증한다.
     *
     * @param imageId   이미지 ID
     * @param productId 소속 확인 대상 상품 ID
     * @return 검증된 ProductImage Entity
     * @throws ImageNotFoundException 미존재 또는 하위리소스 불일치 시
     */
    private ProductImage loadImageForProduct(long imageId, long productId) {
        return productImageRepository.findById(imageId)
                .filter(img -> img.getProduct().getId().equals(productId))
                .orElseThrow(() -> new ImageNotFoundException(imageId));
    }

    /**
     * 상품당 이미지 개수 상한 검사 (plan 1.7).
     * count 쿼리로 현재 이미지 수를 조회하고 상한 도달 시 예외를 던진다.
     * storage.put 호출 이전 단일 지점에서만 수행한다.
     *
     * @param productId 대상 상품 ID
     * @throws ImageLimitExceededException 상한 도달 시
     */
    private void validateImageCount(long productId) {
        int max = storageProperties.getMaxImagesPerProduct();
        if (productImageRepository.countByProductId(productId) >= max) {
            throw new ImageLimitExceededException(max);
        }
    }

    /**
     * 파일 검증 (plan 1.6).
     * Content-Type이 image/로 시작해야 하고, 확장자가 allowedExtensions에 있어야 한다.
     * storage.put 호출 이전 단일 지점에서만 수행한다.
     *
     * @param originalFilename 원본 파일명
     * @param contentType      MIME 타입
     * @throws InvalidImageFileException 검증 실패 시
     */
    private void validateImageFile(String originalFilename, String contentType) {
        // Content-Type 검증
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new InvalidImageFileException("이미지 파일만 업로드할 수 있습니다. (contentType=" + contentType + ")");
        }

        // 확장자 화이트리스트 검증
        String ext = extractExtension(originalFilename);
        List<String> allowed = storageProperties.getAllowedExtensions();
        if (ext.isEmpty() || allowed == null || !allowed.contains(ext.toLowerCase())) {
            throw new InvalidImageFileException("허용되지 않는 파일 확장자입니다. (ext=" + ext + ") 허용: " + allowed);
        }
    }

    /**
     * 파일명에서 확장자(소문자)를 추출한다.
     */
    private String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase();
    }
}
