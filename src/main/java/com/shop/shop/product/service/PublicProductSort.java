package com.shop.shop.product.service;

/**
 * 공개 상품 목록 정렬 기준 enum (product 내부 전용).
 *
 * <p>web이 도메인 enum을 직접 참조하지 않도록 facade가 String → PublicProductSort 변환을 담당한다.
 * 정의 외 String은 LATEST로 폴백한다.
 */
public enum PublicProductSort {

    /**
     * 최신순 (createdAt DESC, id DESC).
     */
    LATEST,

    /**
     * 낮은 가격순 (displayPrice ASC, id ASC).
     */
    PRICE_ASC,

    /**
     * 높은 가격순 (displayPrice DESC, id ASC).
     */
    PRICE_DESC;

    /**
     * String → PublicProductSort 변환. 정의 외 값은 LATEST로 폴백.
     *
     * @param sort 정렬 문자열 (latest/priceAsc/priceDesc, 대소문자 구분 없음)
     * @return PublicProductSort enum 상수
     */
    public static PublicProductSort from(String sort) {
        if (sort == null) {
            return LATEST;
        }
        return switch (sort.toLowerCase()) {
            case "priceasc" -> PRICE_ASC;
            case "pricedesc" -> PRICE_DESC;
            default -> LATEST;
        };
    }
}
