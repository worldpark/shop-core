package com.shop.shop.product.spi;

import com.shop.shop.product.dto.VariantManagementView;

import java.math.BigDecimal;
import java.util.List;

/**
 * 판매자 상품 variant 관리 View 전용 facade (published port).
 *
 * <p>web 모듈의 SellerProductVariantViewController가 product 도메인 내부 Service·Entity·enum을 직접
 * 참조하지 않도록 이 facade를 경유한다. 구현체는 product 내부 {@code service} 패키지에 위치한다.
 *
 * <p>모든 파라미터는 primitive / String / DTO — web이 도메인 타입을 컴파일타임에 참조하지 않는다.
 *
 * <p>의존 방향: web → product.spi (단방향). product는 web을 참조하지 않는다.
 */
public interface SellerProductVariantFacade {

    /**
     * variant 관리 화면 집계 조회.
     *
     * <p>상품 정보 + 옵션 목록(옵션값 포함) + variant 목록을 하나로 묶어 반환한다.
     *
     * @param actorEmail   행위자 이메일 (form login session principal)
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @return variant 관리 화면 DTO
     */
    VariantManagementView getManagementView(String actorEmail, boolean actorIsAdmin, long productId);

    /**
     * 옵션 생성.
     *
     * @param actorEmail   행위자 이메일
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param name         옵션명
     */
    void createOption(String actorEmail, boolean actorIsAdmin, long productId, String name);

    /**
     * 옵션값 생성.
     *
     * @param actorEmail   행위자 이메일
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param optionId     대상 옵션 ID
     * @param value        옵션값 문자열
     */
    void createOptionValue(String actorEmail, boolean actorIsAdmin, long productId,
                           long optionId, String value);

    /**
     * variant 생성.
     *
     * @param actorEmail     행위자 이메일
     * @param actorIsAdmin   행위자 ADMIN 여부
     * @param productId      대상 상품 ID
     * @param sku            SKU
     * @param price          가격 (≥ 0)
     * @param stock          재고 (≥ 0)
     * @param active         활성 여부
     * @param optionValueIds 선택 옵션값 ID 목록
     */
    void createVariant(String actorEmail, boolean actorIsAdmin, long productId,
                       String sku, BigDecimal price, int stock, boolean active, List<Long> optionValueIds);

    /**
     * variant 수정.
     *
     * @param actorEmail     행위자 이메일
     * @param actorIsAdmin   행위자 ADMIN 여부
     * @param productId      대상 상품 ID
     * @param variantId      수정할 variant ID
     * @param sku            수정할 SKU
     * @param price          수정할 가격 (≥ 0)
     * @param stock          수정할 재고 (≥ 0)
     * @param active         수정할 활성 여부
     * @param optionValueIds 수정할 옵션값 ID 목록
     */
    void updateVariant(String actorEmail, boolean actorIsAdmin, long productId, long variantId,
                       String sku, BigDecimal price, int stock, boolean active, List<Long> optionValueIds);
}
