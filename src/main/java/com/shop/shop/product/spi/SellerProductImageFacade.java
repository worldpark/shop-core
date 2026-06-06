package com.shop.shop.product.spi;

import com.shop.shop.product.dto.ProductImageManagementView;
import com.shop.shop.product.dto.ProductImageResponse;

import java.io.InputStream;

/**
 * 판매자 상품 이미지 관리 View 전용 facade (published port).
 *
 * <p>web 모듈의 SellerProductImageViewController가 product 도메인 내부 Service·Entity를 직접
 * 참조하지 않도록 이 facade를 경유한다. 구현체는 product 내부 {@code service} 패키지에 위치한다.
 *
 * <p>모든 파라미터는 primitive / String / InputStream — web이 도메인 타입을 컴파일타임에 참조하지 않는다.
 * MultipartFile은 ViewController에서 getOriginalFilename()/getContentType()/getInputStream()으로
 * 분해해 전달한다 (SellerProductVariantFacade의 primitive 전달 원칙 준수).
 *
 * <p>의존 방향: web → product.spi (단방향). product는 web을 참조하지 않는다.
 */
public interface SellerProductImageFacade {

    /**
     * 이미지 관리 화면 집계 조회.
     *
     * <p>상품 참조 + 이미지 목록을 하나로 묶어 반환한다.
     *
     * @param actorEmail   행위자 이메일 (form login session principal)
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @return 이미지 관리 화면 DTO
     */
    ProductImageManagementView getManagementView(String actorEmail, boolean actorIsAdmin, long productId);

    /**
     * 이미지 업로드.
     *
     * <p>ViewController에서 MultipartFile의 메타데이터와 스트림을 분해해 전달한다.
     * 스트림은 ViewController가 try-with-resources로 열어 facade 호출을 그 안에서 수행해야 한다.
     *
     * @param actorEmail       행위자 이메일
     * @param actorIsAdmin     행위자 ADMIN 여부
     * @param productId        대상 상품 ID
     * @param originalFilename 원본 파일명 (확장자 검증용)
     * @param contentType      MIME 타입 (검증용)
     * @param inputStream      파일 데이터 스트림
     * @return 업로드된 이미지 응답 DTO
     */
    ProductImageResponse upload(String actorEmail, boolean actorIsAdmin, long productId,
                                String originalFilename, String contentType, InputStream inputStream);

    /**
     * 대표 이미지 지정.
     *
     * @param actorEmail   행위자 이메일
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param imageId      대표로 지정할 이미지 ID
     * @return 대표로 지정된 이미지 응답 DTO
     */
    ProductImageResponse setPrimary(String actorEmail, boolean actorIsAdmin, long productId, long imageId);

    /**
     * 정렬 순서 변경.
     *
     * @param actorEmail   행위자 이메일
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param imageId      변경할 이미지 ID
     * @param sortOrder    새 정렬 순서
     * @return 변경된 이미지 응답 DTO
     */
    ProductImageResponse changeOrder(String actorEmail, boolean actorIsAdmin, long productId, long imageId,
                                     int sortOrder);

    /**
     * 이미지 삭제.
     *
     * @param actorEmail   행위자 이메일
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param imageId      삭제할 이미지 ID
     */
    void delete(String actorEmail, boolean actorIsAdmin, long productId, long imageId);
}
