package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.common.exception.CouponConflictException;
import com.shop.shop.common.exception.CouponNotApplicableException;
import com.shop.shop.common.exception.CouponNotFoundException;
import com.shop.shop.order.domain.Coupon;
import com.shop.shop.order.domain.UserCoupon;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CouponService.computeDiscount / consume 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>미사용/이미사용/유효기간/비활성/최소주문금액/소유권 위반 각 분기</li>
 *   <li>경합 패자(markUsedIfUnused=0 → CouponConflictException, incrementUsedCount=0 → CouponConflictException)</li>
 *   <li>computeDiscount가 올바른 discountAmount 산정</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponServiceApplyTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private CartCheckoutReader cartCheckoutReader;
    @Mock
    private ProductOrderCatalog productOrderCatalog;

    private CouponService couponService;

    private static final long USER_ID = 1L;
    private static final long USER_COUPON_ID = 100L;
    private static final long COUPON_ID = 99L;
    private static final long ORDER_ID = 500L;

    private static final Instant STARTS = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant ENDS = Instant.parse("2027-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        couponService = new CouponService(couponRepository, userCouponRepository, cartCheckoutReader, productOrderCatalog);
    }

    // ============================================================
    // computeDiscount — 성공
    // ============================================================

    @Test
    @DisplayName("computeDiscount 성공 — 올바른 discountAmount 반환")
    void computeDiscount_success_returnsCorrectAmount() {
        UserCoupon userCoupon = buildUnusedUserCoupon();
        Coupon coupon = buildActiveCoupon("fixed", "1000", BigDecimal.ZERO, null);

        when(userCouponRepository.findByIdAndUserId(USER_COUPON_ID, USER_ID)).thenReturn(Optional.of(userCoupon));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        CouponService.AppliedDiscount result = couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("5000"));

        assertThat(result.discountAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.couponId()).isEqualTo(COUPON_ID);
    }

    @Test
    @DisplayName("computeDiscount percent 할인 — floor + max_discount")
    void computeDiscount_percent_floorAndCap() {
        UserCoupon userCoupon = buildUnusedUserCoupon();
        Coupon coupon = buildActiveCoupon("percent", "10", BigDecimal.ZERO, new BigDecimal("500"));

        when(userCouponRepository.findByIdAndUserId(USER_COUPON_ID, USER_ID)).thenReturn(Optional.of(userCoupon));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        // 10% of 10000 = 1000, but max_discount = 500
        CouponService.AppliedDiscount result = couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("10000"));

        assertThat(result.discountAmount()).isEqualByComparingTo("500.00");
    }

    // ============================================================
    // computeDiscount — 실패 분기
    // ============================================================

    @Test
    @DisplayName("미보유/타인 소유 userCoupon → CouponNotFoundException(404)")
    void computeDiscount_notOwned_throwsCouponNotFoundException() {
        when(userCouponRepository.findByIdAndUserId(USER_COUPON_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("5000")))
                .isInstanceOf(CouponNotFoundException.class);
    }

    @Test
    @DisplayName("이미 사용된 userCoupon → CouponConflictException(409)")
    void computeDiscount_alreadyUsed_throwsCouponConflictException() {
        UserCoupon usedUc = buildUsedUserCoupon();
        when(userCouponRepository.findByIdAndUserId(USER_COUPON_ID, USER_ID)).thenReturn(Optional.of(usedUc));

        assertThatThrownBy(() -> couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("5000")))
                .isInstanceOf(CouponConflictException.class)
                .hasMessageContaining("이미 사용된");
    }

    @Test
    @DisplayName("유효기간 이후 쿠폰 → CouponNotApplicableException(400)")
    void computeDiscount_expiredCoupon_throwsCouponNotApplicableException() {
        UserCoupon userCoupon = buildUnusedUserCoupon();
        Instant pastStart = Instant.parse("2020-01-01T00:00:00Z");
        Instant pastEnd = Instant.parse("2020-12-31T23:59:59Z");
        Coupon expiredCoupon = Coupon.create("CODE", "만료", "fixed", BigDecimal.ONE,
                BigDecimal.ZERO, null, pastStart, pastEnd, null, true);
        setCouponId(expiredCoupon, COUPON_ID);

        when(userCouponRepository.findByIdAndUserId(USER_COUPON_ID, USER_ID)).thenReturn(Optional.of(userCoupon));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(expiredCoupon));

        assertThatThrownBy(() -> couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("5000")))
                .isInstanceOf(CouponNotApplicableException.class)
                .hasMessageContaining("사용 기간");
    }

    @Test
    @DisplayName("비활성 쿠폰 → CouponNotApplicableException(400)")
    void computeDiscount_inactiveCoupon_throwsCouponNotApplicableException() {
        UserCoupon userCoupon = buildUnusedUserCoupon();
        Coupon inactiveCoupon = Coupon.create("CODE", "비활성", "fixed", BigDecimal.ONE,
                BigDecimal.ZERO, null, STARTS, ENDS, null, false);
        setCouponId(inactiveCoupon, COUPON_ID);

        when(userCouponRepository.findByIdAndUserId(USER_COUPON_ID, USER_ID)).thenReturn(Optional.of(userCoupon));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(inactiveCoupon));

        assertThatThrownBy(() -> couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("5000")))
                .isInstanceOf(CouponNotApplicableException.class)
                .hasMessageContaining("비활성");
    }

    @Test
    @DisplayName("최소주문금액 미달 → CouponNotApplicableException(400)")
    void computeDiscount_belowMinOrderAmount_throwsCouponNotApplicableException() {
        UserCoupon userCoupon = buildUnusedUserCoupon();
        Coupon coupon = buildActiveCoupon("fixed", "1000", new BigDecimal("5000"), null); // min=5000

        when(userCouponRepository.findByIdAndUserId(USER_COUPON_ID, USER_ID)).thenReturn(Optional.of(userCoupon));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        // itemsAmount=3000 < minOrderAmount=5000
        assertThatThrownBy(() -> couponService.computeDiscount(USER_ID, USER_COUPON_ID, new BigDecimal("3000")))
                .isInstanceOf(CouponNotApplicableException.class)
                .hasMessageContaining("최소 주문금액");
    }

    // ============================================================
    // consume — 경합 패자
    // ============================================================

    @Test
    @DisplayName("markUsedIfUnused=0 → CouponConflictException(409) — 동시 사용 경합 패자")
    void consume_markUsedIfUnusedReturns0_throwsCouponConflictException() {
        when(userCouponRepository.markUsedIfUnused(anyLong(), anyLong(), anyLong(),
                any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> couponService.consume(USER_COUPON_ID, USER_ID, COUPON_ID, ORDER_ID, Instant.now()))
                .isInstanceOf(CouponConflictException.class)
                .hasMessageContaining("사용할 수 없습니다");
    }

    @Test
    @DisplayName("incrementUsedCountIfWithinLimit=0 → CouponConflictException(409) — 한도 소진")
    void consume_incrementUsedCountReturns0_throwsCouponConflictException() {
        when(userCouponRepository.markUsedIfUnused(anyLong(), anyLong(), anyLong(),
                any(Instant.class)))
                .thenReturn(1);
        when(couponRepository.incrementUsedCountIfWithinLimit(COUPON_ID)).thenReturn(0);

        assertThatThrownBy(() -> couponService.consume(USER_COUPON_ID, USER_ID, COUPON_ID, ORDER_ID, Instant.now()))
                .isInstanceOf(CouponConflictException.class)
                .hasMessageContaining("한도");
    }

    @Test
    @DisplayName("consume 성공 — 두 UPDATE 모두 호출됨")
    void consume_success_callsBothUpdates() {
        Instant now = Instant.now();
        when(userCouponRepository.markUsedIfUnused(USER_COUPON_ID, USER_ID, ORDER_ID, now)).thenReturn(1);
        when(couponRepository.incrementUsedCountIfWithinLimit(COUPON_ID)).thenReturn(1);

        couponService.consume(USER_COUPON_ID, USER_ID, COUPON_ID, ORDER_ID, now);

        verify(userCouponRepository).markUsedIfUnused(USER_COUPON_ID, USER_ID, ORDER_ID, now);
        verify(couponRepository).incrementUsedCountIfWithinLimit(COUPON_ID);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private UserCoupon buildUnusedUserCoupon() {
        UserCoupon uc = UserCoupon.issue(USER_ID, COUPON_ID, Instant.now());
        setId(uc, USER_COUPON_ID);
        return uc;
    }

    private UserCoupon buildUsedUserCoupon() {
        UserCoupon uc = UserCoupon.issue(USER_ID, COUPON_ID, Instant.parse("2026-06-01T00:00:00Z"));
        setId(uc, USER_COUPON_ID);
        // usedAt 설정 via reflection
        try {
            java.lang.reflect.Field f = UserCoupon.class.getDeclaredField("usedAt");
            f.setAccessible(true);
            f.set(uc, Instant.parse("2026-06-10T00:00:00Z"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return uc;
    }

    private Coupon buildActiveCoupon(String type, String value, BigDecimal minOrder, BigDecimal maxDiscount) {
        Coupon c = Coupon.create("CODE", "쿠폰", type, new BigDecimal(value),
                minOrder, maxDiscount, STARTS, ENDS, null, true);
        setCouponId(c, COUPON_ID);
        return c;
    }

    private void setId(Object obj, long id) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(obj, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setCouponId(Coupon coupon, long id) {
        setId(coupon, id);
    }
}
