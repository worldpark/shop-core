package com.shop.shop.product.service;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.dto.ProductImageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * 상품 이미지 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link ProductImageService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다.
 *
 * <p>REST principal 추출:
 * JWT 필터 후 principal = userId(long). {@code (long) auth.getPrincipal()}로 actorId 추출.
 * actorIsAdmin: auth.getAuthorities()에 'ROLE_ADMIN' 직접 보유 여부.
 *
 * <p>레이어: *RestController → ProductImageServiceResponse → ProductImageService → *Repository
 */
@Service
@RequiredArgsConstructor
public class ProductImageServiceResponse {

    private final ProductImageService productImageService;
    private final AssetUrlResolver assetUrlResolver;

    /**
     * 상품 이미지 목록 조회 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @return 이미지 목록 응답 DTO
     */
    public List<ProductImageResponse> listImages(Authentication auth, long productId) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        return productImageService.listImages(actorId, actorIsAdmin, productId).stream()
                .map(image -> ProductImageResponse.from(image, assetUrlResolver))
                .toList();
    }

    /**
     * 이미지 업로드 — REST 전용.
     *
     * @param auth             JWT 인증 객체
     * @param productId        대상 상품 ID
     * @param originalFilename 원본 파일명
     * @param contentType      MIME 타입
     * @param inputStream      파일 데이터 스트림
     * @return 업로드된 이미지 응답 DTO
     */
    public ProductImageResponse upload(Authentication auth, long productId,
                                        String originalFilename, String contentType, InputStream inputStream) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        var image = productImageService.upload(actorId, actorIsAdmin, productId, originalFilename, contentType, inputStream);
        return ProductImageResponse.from(image, assetUrlResolver);
    }

    /**
     * 대표 이미지 지정 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param imageId   대표로 지정할 이미지 ID
     * @return 대표로 지정된 이미지 응답 DTO
     */
    public ProductImageResponse setPrimary(Authentication auth, long productId, long imageId) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        var image = productImageService.setPrimary(actorId, actorIsAdmin, productId, imageId);
        return ProductImageResponse.from(image, assetUrlResolver);
    }

    /**
     * 정렬 순서 변경 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param imageId   변경할 이미지 ID
     * @param sortOrder 새 정렬 순서
     * @return 변경된 이미지 응답 DTO
     */
    public ProductImageResponse changeOrder(Authentication auth, long productId, long imageId, int sortOrder) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        var image = productImageService.changeOrder(actorId, actorIsAdmin, productId, imageId, sortOrder);
        return ProductImageResponse.from(image, assetUrlResolver);
    }

    /**
     * 이미지 삭제 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param imageId   삭제할 이미지 ID
     */
    public void delete(Authentication auth, long productId, long imageId) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        productImageService.delete(actorId, actorIsAdmin, productId, imageId);
    }

    /**
     * ROLE_ADMIN 직접 보유 여부 판정.
     */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
