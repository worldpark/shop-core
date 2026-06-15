package com.shop.shop.web.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 리뷰 작성/수정 폼 백킹 객체.
 *
 * <p>GET /reviews/new?orderItemId= 폼 바인딩 및 POST /reviews, POST /reviews/{id}/edit 제출에 사용한다.
 * 수정 시에는 reviewId도 함께 보유한다.
 *
 * <p>모델 키: reviewForm (Thymeleaf 예약어 회피 — request/param/application/session 금지).
 */
@Getter
@Setter
@NoArgsConstructor
public class ReviewForm {

    /** 주문 항목 ID (작성 시 사용, 수정 시 불필요). */
    private Long orderItemId;

    /** 리뷰 ID (수정/삭제 시 사용). */
    private Long reviewId;

    @NotNull(message = "평점을 선택해주세요.")
    @Min(value = 1, message = "평점은 최소 1점입니다.")
    @Max(value = 5, message = "평점은 최대 5점입니다.")
    private Integer rating;

    @Size(max = 1000, message = "리뷰 내용은 최대 1000자입니다.")
    private String content;
}
