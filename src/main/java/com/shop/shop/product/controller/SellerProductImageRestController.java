package com.shop.shop.product.controller;

import com.shop.shop.product.dto.ProductImageResponse;
import com.shop.shop.product.service.ProductImageServiceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * SELLER 상품 이미지 관리 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/seller/**} → {@code hasRole("SELLER")}.
 * RoleHierarchy(ADMIN > SELLER > CONSUMER)로 ADMIN 함의. CONSUMER → 403, 비인증 → 401.
 * 소유권 검사는 ProductService에서 수행 (타인 상품 → 404).
 * 비즈니스 로직 없음 — {@link ProductImageServiceResponse}에 위임.
 */
@RestController
@RequestMapping("/api/v1/seller/products/{productId}/images")
@RequiredArgsConstructor
public class SellerProductImageRestController {

    private final ProductImageServiceResponse productImageServiceResponse;

    /**
     * 상품 이미지 목록 조회 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param auth      JWT 인증 객체
     * @return 200 이미지 목록 응답 DTO
     */
    @GetMapping
    public ResponseEntity<List<ProductImageResponse>> listImages(
            @PathVariable long productId,
            Authentication auth) {
        return ResponseEntity.ok(productImageServiceResponse.listImages(auth, productId));
    }

    /**
     * 이미지 업로드 (SELLER 이상, 소유권 검사 포함).
     *
     * <p>multipart/form-data, part 이름: file.
     * 파일 검증(MIME/확장자)은 ProductImageService에서 수행한다.
     *
     * @param productId 대상 상품 ID
     * @param file      업로드 파일 (multipart)
     * @param auth      JWT 인증 객체
     * @return 200 업로드된 이미지 응답 DTO
     */
    @PostMapping
    public ResponseEntity<ProductImageResponse> uploadImage(
            @PathVariable long productId,
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(productImageServiceResponse.upload(
                auth, productId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getInputStream()
        ));
    }

    /**
     * 대표 이미지 지정 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param imageId   대표로 지정할 이미지 ID
     * @param auth      JWT 인증 객체
     * @return 200 대표로 지정된 이미지 응답 DTO
     */
    @PatchMapping("/{imageId}/primary")
    public ResponseEntity<ProductImageResponse> setPrimary(
            @PathVariable long productId,
            @PathVariable long imageId,
            Authentication auth) {
        return ResponseEntity.ok(productImageServiceResponse.setPrimary(auth, productId, imageId));
    }

    /**
     * 정렬 순서 변경 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param imageId   변경할 이미지 ID
     * @param sortOrder 새 정렬 순서 (request body)
     * @param auth      JWT 인증 객체
     * @return 200 변경된 이미지 응답 DTO
     */
    @PatchMapping("/{imageId}/order")
    public ResponseEntity<ProductImageResponse> changeOrder(
            @PathVariable long productId,
            @PathVariable long imageId,
            @RequestParam int sortOrder,
            Authentication auth) {
        return ResponseEntity.ok(productImageServiceResponse.changeOrder(auth, productId, imageId, sortOrder));
    }

    /**
     * 이미지 삭제 (SELLER 이상, 소유권 검사 포함).
     *
     * @param productId 대상 상품 ID
     * @param imageId   삭제할 이미지 ID
     * @param auth      JWT 인증 객체
     * @return 204 No Content
     */
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> deleteImage(
            @PathVariable long productId,
            @PathVariable long imageId,
            Authentication auth) {
        productImageServiceResponse.delete(auth, productId, imageId);
        return ResponseEntity.noContent().build();
    }
}
