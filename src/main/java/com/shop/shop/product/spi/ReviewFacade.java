package com.shop.shop.product.spi;

import com.shop.shop.product.dto.ProductReviewSummaryResponse;

/**
 * 리뷰 View 전용 facade (published port).
 *
 * <p>web 모듈의 ReviewViewController가 product 도메인 내부 Service·Entity를 직접 참조하지 않도록 경유한다.
 * 시그니처는 web 타입 비참조(String email + 스칼라/포트 DTO).
 * 구현체는 product 내부 {@code service} 패키지에 위치한다.
 *
 * <p>의존 방향: web → product.spi (단방향). product는 web을 참조하지 않는다.
 */
public interface ReviewFacade {

    /**
     * 상품 리뷰 목록 + 집계 조회 (공개 — 비로그인 가능).
     *
     * @param productId 상품 ID
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 리뷰 목록 + 집계 응답
     */
    ProductReviewSummaryResponse getProductReviews(long productId, int page, int size);

    /**
     * 상품 상세 화면에서 현재 사용자가 리뷰를 작성할 수 있는 주문 항목 id 조회 (View 경로 — email로 userId 해석).
     *
     * <p>배송 완료했고 아직 리뷰를 쓰지 않은 항목이 있으면 그 id를, 없으면 null을 반환한다.
     * 컨트롤러는 이 값으로 "리뷰 작성" 버튼 노출 여부와 {@code ?orderItemId=} 링크를 구성한다.
     *
     * @param email     인증 세션 email
     * @param productId 상품 ID
     * @return 작성 가능한 order_item id (없으면 null)
     */
    Long findWritableOrderItemId(String email, long productId);

    /**
     * 리뷰 작성 (View 경로 — email로 userId 해석).
     *
     * @param email       인증 세션 email
     * @param orderItemId 주문 항목 ID
     * @param rating      평점 (1~5)
     * @param content     내용 (nullable)
     * @return 작성된 리뷰의 productId (redirect용)
     */
    long create(String email, long orderItemId, int rating, String content);

    /**
     * 리뷰 수정 (View 경로 — email로 userId 해석).
     *
     * @param email    인증 세션 email
     * @param reviewId 수정할 리뷰 ID
     * @param rating   새 평점 (1~5)
     * @param content  새 내용 (nullable)
     * @return 수정된 리뷰의 productId (redirect용)
     */
    long edit(String email, long reviewId, int rating, String content);

    /**
     * 리뷰 삭제 (View 경로 — email로 userId 해석).
     *
     * <p>REST 측 void와 달리 View는 삭제 후 /products/{productId}로 PRG redirect해야 하므로
     * redirect 대상 산출용 productId를 반환한다.
     *
     * @param email    인증 세션 email
     * @param reviewId 삭제할 리뷰 ID
     * @return 삭제된 리뷰의 productId (redirect용)
     */
    long delete(String email, long reviewId);
}
