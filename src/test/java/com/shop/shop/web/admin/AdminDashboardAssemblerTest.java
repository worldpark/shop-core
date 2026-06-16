package com.shop.shop.web.admin;

import com.shop.shop.member.spi.AdminMemberFacade;
import com.shop.shop.order.spi.AdminOrderStatsFacade;
import com.shop.shop.product.spi.AdminProductStatsFacade;
import com.shop.shop.web.admin.dto.AdminDashboardView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminDashboardAssembler} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>3개 비율 계산 정확성 (num/den, scale 1 HALF_UP)</li>
 *   <li>분모 0 가드 — den=0이면 ratioPercent=null (division by zero 없음)</li>
 *   <li>soldVids 빈 케이스 — productFacade에 빈 컬렉션 전달</li>
 *   <li>periodLabel 고정값</li>
 * </ul>
 *
 * <p>Facade는 @Mock으로 주입해 단위 검증. DB·Testcontainers 불필요.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardAssemblerTest {

    @Mock
    private AdminMemberFacade memberFacade;

    @Mock
    private AdminOrderStatsFacade orderFacade;

    @Mock
    private AdminProductStatsFacade productFacade;

    private AdminDashboardAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new AdminDashboardAssembler(memberFacade, orderFacade, productFacade);
    }

    // ============================================================
    // 비율 계산 정확성
    // ============================================================

    @Test
    @DisplayName("유저 이용률: 60/200 → 30.0%")
    void build_memberActivity_ratio_calculated_correctly() {
        givenMemberFacade(60, 200);
        givenOrderFacade(0, 0, List.of());
        givenProductFacade(0, 0);

        AdminDashboardView view = assembler.build();

        AdminDashboardView.Metric metric = view.memberActivity();
        assertThat(metric.numerator()).isEqualTo(60L);
        assertThat(metric.denominator()).isEqualTo(200L);
        assertThat(metric.ratioPercent()).isEqualByComparingTo(new BigDecimal("30.0"));
    }

    @Test
    @DisplayName("상품 판매율: 3/10 → 30.0%")
    void build_productSales_ratio_calculated_correctly() {
        givenMemberFacade(0, 1);
        List<Long> soldVids = List.of(1L, 2L, 3L);
        when(orderFacade.distinctSoldVariantIdsSince(any(Instant.class))).thenReturn(soldVids);
        when(orderFacade.countOrdersSince(any(Instant.class))).thenReturn(1L);
        when(orderFacade.countRefundedSince(any(Instant.class))).thenReturn(0L);
        when(productFacade.countPublishedProductsWithSales(soldVids)).thenReturn(3L);
        when(productFacade.countPublishedProducts()).thenReturn(10L);

        AdminDashboardView view = assembler.build();

        AdminDashboardView.Metric metric = view.productSales();
        assertThat(metric.numerator()).isEqualTo(3L);
        assertThat(metric.denominator()).isEqualTo(10L);
        assertThat(metric.ratioPercent()).isEqualByComparingTo(new BigDecimal("30.0"));
    }

    @Test
    @DisplayName("환불율: 1/3 → 33.3% (HALF_UP)")
    void build_refundRate_ratio_rounding_half_up() {
        givenMemberFacade(0, 1);
        givenOrderFacade(1, 3, List.of());
        givenProductFacade(0, 1);

        AdminDashboardView view = assembler.build();

        AdminDashboardView.Metric metric = view.refundRate();
        assertThat(metric.numerator()).isEqualTo(1L);
        assertThat(metric.denominator()).isEqualTo(3L);
        // 1*100 / 3 = 33.333... → scale 1, HALF_UP → 33.3
        assertThat(metric.ratioPercent()).isEqualByComparingTo(new BigDecimal("33.3"));
    }

    @Test
    @DisplayName("환불율: 2/3 → 66.7% (HALF_UP)")
    void build_refundRate_ratio_rounding_two_thirds() {
        givenMemberFacade(0, 1);
        givenOrderFacade(2, 3, List.of());
        givenProductFacade(0, 1);

        AdminDashboardView view = assembler.build();

        AdminDashboardView.Metric metric = view.refundRate();
        // 2*100 / 3 = 66.666... → scale 1, HALF_UP → 66.7
        assertThat(metric.ratioPercent()).isEqualByComparingTo(new BigDecimal("66.7"));
    }

    @Test
    @DisplayName("이용률 100%: num == den")
    void build_memberActivity_full_ratio() {
        givenMemberFacade(100, 100);
        givenOrderFacade(0, 1, List.of());
        givenProductFacade(0, 1);

        AdminDashboardView view = assembler.build();

        assertThat(view.memberActivity().ratioPercent()).isEqualByComparingTo(new BigDecimal("100.0"));
    }

    // ============================================================
    // 분모 0 가드
    // ============================================================

    @Test
    @DisplayName("유저 이용률 — activeMembers=0 이면 ratioPercent=null (division by zero 없음)")
    void build_memberActivity_denominator_zero_returns_null_ratio() {
        givenMemberFacade(0, 0);
        givenOrderFacade(0, 1, List.of());
        givenProductFacade(0, 1);

        AdminDashboardView view = assembler.build();

        AdminDashboardView.Metric metric = view.memberActivity();
        assertThat(metric.ratioPercent()).isNull();
        assertThat(metric.denominator()).isEqualTo(0L);
    }

    @Test
    @DisplayName("상품 판매율 — publishedProducts=0 이면 ratioPercent=null")
    void build_productSales_denominator_zero_returns_null_ratio() {
        givenMemberFacade(0, 1);
        givenOrderFacade(0, 1, List.of());
        givenProductFacade(0, 0);

        AdminDashboardView view = assembler.build();

        AdminDashboardView.Metric metric = view.productSales();
        assertThat(metric.ratioPercent()).isNull();
        assertThat(metric.denominator()).isEqualTo(0L);
    }

    @Test
    @DisplayName("환불율 — totalOrders=0 이면 ratioPercent=null")
    void build_refundRate_denominator_zero_returns_null_ratio() {
        givenMemberFacade(0, 1);
        givenOrderFacade(0, 0, List.of());
        givenProductFacade(0, 1);

        AdminDashboardView view = assembler.build();

        AdminDashboardView.Metric metric = view.refundRate();
        assertThat(metric.ratioPercent()).isNull();
        assertThat(metric.denominator()).isEqualTo(0L);
    }

    @Test
    @DisplayName("3개 지표 모두 분모=0 이면 모두 ratioPercent=null (전수 가드)")
    void build_all_denominators_zero_all_ratios_null() {
        givenMemberFacade(0, 0);
        givenOrderFacade(0, 0, List.of());
        givenProductFacade(0, 0);

        AdminDashboardView view = assembler.build();

        assertThat(view.memberActivity().ratioPercent()).isNull();
        assertThat(view.productSales().ratioPercent()).isNull();
        assertThat(view.refundRate().ratioPercent()).isNull();
    }

    // ============================================================
    // soldVids 빈 케이스
    // ============================================================

    @Test
    @DisplayName("soldVids 빈 목록 — productFacade.countPublishedProductsWithSales 에 빈 컬렉션 전달")
    void build_empty_soldVids_passed_to_product_facade() {
        givenMemberFacade(0, 1);
        givenOrderFacade(0, 1, List.of());  // 빈 soldVids
        givenProductFacade(0, 5);

        AdminDashboardView view = assembler.build();

        // productFacade.countPublishedProductsWithSales가 빈 컬렉션으로 호출됨
        verify(productFacade).countPublishedProductsWithSales(List.of());
        // 분자=0, 분모=5 → 0.0%
        assertThat(view.productSales().ratioPercent()).isEqualByComparingTo(new BigDecimal("0.0"));
        assertThat(view.productSales().numerator()).isEqualTo(0L);
    }

    @Test
    @DisplayName("soldVids 비어 있을 때 상품 판매율 분자=0, 비율=0.0%")
    void build_empty_soldVids_product_sales_ratio_is_zero() {
        givenMemberFacade(5, 100);
        givenOrderFacade(0, 10, List.of());  // 빈 soldVids, 주문 10건
        givenProductFacade(0, 20);           // 판매된 게시상품 0, 전체 게시상품 20

        AdminDashboardView view = assembler.build();

        assertThat(view.productSales().numerator()).isEqualTo(0L);
        assertThat(view.productSales().denominator()).isEqualTo(20L);
        assertThat(view.productSales().ratioPercent()).isEqualByComparingTo(new BigDecimal("0.0"));
    }

    // ============================================================
    // periodLabel
    // ============================================================

    @Test
    @DisplayName("periodLabel은 '최근 30일' 고정")
    void build_period_label_is_recent_30_days() {
        givenMemberFacade(0, 1);
        givenOrderFacade(0, 1, List.of());
        givenProductFacade(0, 1);

        AdminDashboardView view = assembler.build();

        assertThat(view.periodLabel()).isEqualTo("최근 30일");
    }

    // ============================================================
    // helpers
    // ============================================================

    private void givenMemberFacade(long loggedIn, long active) {
        when(memberFacade.countActiveMembersLoggedInSince(any(Instant.class))).thenReturn(loggedIn);
        when(memberFacade.countActiveMembers()).thenReturn(active);
    }

    /**
     * orderFacade stub 헬퍼.
     *
     * @param refunded  refunded 주문수
     * @param total     전체 주문수
     * @param soldVids  판매 variantId 목록
     */
    private void givenOrderFacade(long refunded, long total, List<Long> soldVids) {
        when(orderFacade.countRefundedSince(any(Instant.class))).thenReturn(refunded);
        when(orderFacade.countOrdersSince(any(Instant.class))).thenReturn(total);
        when(orderFacade.distinctSoldVariantIdsSince(any(Instant.class))).thenReturn(soldVids);
    }

    private void givenProductFacade(long withSales, long published) {
        when(productFacade.countPublishedProductsWithSales(anyCollection())).thenReturn(withSales);
        when(productFacade.countPublishedProducts()).thenReturn(published);
    }
}
