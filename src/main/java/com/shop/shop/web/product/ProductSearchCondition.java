package com.shop.shop.web.product;

/**
 * 공개 상품 목록 검색 조건 객체 (View 전용).
 *
 * <p>모델 키: searchCondition.
 * 검색 폼·정렬 셀렉트·페이징 링크에서 현재 조건 유지에 사용한다.
 *
 * <p>sort 기본값: "latest". 정의 외 값은 facade 내부에서 latest 폴백.
 */
public class ProductSearchCondition {

    private String keyword;
    private Long categoryId;
    private String sort;
    private int page;
    private int size;

    public ProductSearchCondition() {
        this.sort = "latest";
        this.page = 0;
        this.size = 20;
    }

    public ProductSearchCondition(String keyword, Long categoryId, String sort, int page, int size) {
        this.keyword = keyword;
        this.categoryId = categoryId;
        this.sort = (sort != null && !sort.isBlank()) ? sort : "latest";
        this.page = page;
        this.size = size;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
