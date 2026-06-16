package com.shop.shop.web.admin;

import com.shop.shop.member.spi.AdminMemberFacade;
import com.shop.shop.order.spi.AdminOrderStatsFacade;
import com.shop.shop.product.spi.AdminProductStatsFacade;
import com.shop.shop.web.admin.dto.AdminDashboardView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 관리자 통계 대시보드 데이터 조합 컴포넌트.
 *
 * <p>member.spi·order.spi·product.spi 3개 facade를 조합해 {@link AdminDashboardView}를 생성한다.
 * 비율 계산은 web 조합 지점에서만 수행한다(각 도메인 모듈에 비율 계산 없음).
 *
 * <p>Clock 빈 미사용 — 기간 임계시각은 {@code Instant.now()}로 직접 계산(KST 변환 불필요).
 *
 * <p>모듈 경계:
 * <ul>
 *   <li>web → member.spi, order.spi, product.spi (단방향 forward, 사이클 없음)</li>
 *   <li>order는 variantId(Long)만 노출 — product 타입 누설 없음</li>
 *   <li>비율 계산은 이 클래스에서만 수행</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AdminDashboardAssembler {

    private static final String PERIOD_LABEL = "최근 30일";
    private static final int PERIOD_DAYS = 30;

    private final AdminMemberFacade memberFacade;
    private final AdminOrderStatsFacade orderFacade;
    private final AdminProductStatsFacade productFacade;

    /**
     * 3개 통계 지표를 조합해 대시보드 뷰 모델을 생성한다.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>30일 임계시각 계산 ({@code Instant.now().minus(30, DAYS)})</li>
     *   <li>유저 이용률: 최근 30일 접속 활성 회원 / 전체 활성 회원</li>
     *   <li>상품 판매율: 최근 30일 판매 variantId → 게시 상품 distinct / 전체 게시 상품</li>
     *   <li>환불율: 최근 30일 환불 주문수 / 최근 30일 전체 주문수</li>
     * </ol>
     *
     * @return {@link AdminDashboardView} — 분모 0인 지표는 ratioPercent=null
     */
    public AdminDashboardView build() {
        Instant threshold = Instant.now().minus(PERIOD_DAYS, ChronoUnit.DAYS);

        // 1. 유저 이용률
        long activeMembersLoggedIn = memberFacade.countActiveMembersLoggedInSince(threshold);
        long activeMembers = memberFacade.countActiveMembers();
        AdminDashboardView.Metric memberActivity = toMetric(activeMembersLoggedIn, activeMembers);

        // 2. 상품 판매율
        List<Long> soldVids = orderFacade.distinctSoldVariantIdsSince(threshold);
        long productsWithSales = productFacade.countPublishedProductsWithSales(soldVids);
        long publishedProducts = productFacade.countPublishedProducts();
        AdminDashboardView.Metric productSales = toMetric(productsWithSales, publishedProducts);

        // 3. 환불율
        long refundedOrders = orderFacade.countRefundedSince(threshold);
        long totalOrders = orderFacade.countOrdersSince(threshold);
        AdminDashboardView.Metric refundRate = toMetric(refundedOrders, totalOrders);

        return new AdminDashboardView(memberActivity, productSales, refundRate, PERIOD_LABEL);
    }

    /**
     * 분자/분모로 비율 지표를 생성한다.
     *
     * <p>분모가 0이면 {@link AdminDashboardView.Metric#ratioPercent()}는 {@code null}(표시용 가드).
     * BigDecimal 나눗셈은 scale 1, HALF_UP 반올림.
     *
     * @param numerator   분자
     * @param denominator 분모
     * @return 비율 지표 — den==0이면 ratioPercent=null
     */
    private AdminDashboardView.Metric toMetric(long numerator, long denominator) {
        if (denominator == 0) {
            return new AdminDashboardView.Metric(null, numerator, denominator);
        }
        BigDecimal ratio = BigDecimal.valueOf(numerator * 100L)
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
        return new AdminDashboardView.Metric(ratio, numerator, denominator);
    }
}
