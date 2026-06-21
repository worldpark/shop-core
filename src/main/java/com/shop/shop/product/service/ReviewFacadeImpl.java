package com.shop.shop.product.service;

import com.shop.shop.product.dto.ProductReviewSummaryResponse;
import com.shop.shop.product.spi.ReviewFacade;
import com.shop.shop.product.spi.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewFacade} 구현체 (package-private).
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link ReviewFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>email → userId 변환: {@link UserDirectory#findUserIdByEmail(String)}</li>
 *   <li>{@link ReviewService} 위임</li>
 *   <li>내부 result record → 응답 DTO 변환</li>
 * </ul>
 *
 * <p>View 전용 facade — ReviewServiceResponse(REST 전용)와 독립.
 */
@Service
@RequiredArgsConstructor
class ReviewFacadeImpl implements ReviewFacade {

    private final ReviewService reviewService;
    private final ReviewDtoMapper reviewDtoMapper;
    private final UserDirectory userDirectory;

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public ProductReviewSummaryResponse getProductReviews(long productId, int page, int size) {
        ReviewService.ReviewSummaryResult result = reviewService.getProductReviews(productId, page, size);
        return reviewDtoMapper.toSummaryResponse(result);
    }

    /**
     * {@inheritDoc}
     *
     * <p>email → userId 변환 후 ReviewService.findWritableOrderItemId에 위임한다.
     */
    @Override
    @Transactional(readOnly = true)
    public Long findWritableOrderItemId(String email, long productId) {
        long userId = userDirectory.findUserIdByEmail(email);
        return reviewService.findWritableOrderItemId(userId, productId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>email → userId 변환 후 ReviewService.create에 위임한다.
     */
    @Override
    @Transactional
    public long create(String email, long orderItemId, int rating, String content) {
        long userId = userDirectory.findUserIdByEmail(email);
        ReviewService.ReviewResult result = reviewService.create(userId, orderItemId, rating, content);
        return result.productId();
    }

    /**
     * {@inheritDoc}
     *
     * <p>email → userId 변환 후 ReviewService.edit에 위임한다.
     */
    @Override
    @Transactional
    public long edit(String email, long reviewId, int rating, String content) {
        long userId = userDirectory.findUserIdByEmail(email);
        ReviewService.ReviewResult result = reviewService.edit(userId, reviewId, rating, content);
        return result.productId();
    }

    /**
     * {@inheritDoc}
     *
     * <p>email → userId 변환 후 ReviewService.delete에 위임한다.
     */
    @Override
    @Transactional
    public long delete(String email, long reviewId) {
        long userId = userDirectory.findUserIdByEmail(email);
        return reviewService.delete(userId, reviewId);
    }
}
