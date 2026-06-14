package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.common.exception.CouponAlreadyOwnedException;
import com.shop.shop.common.exception.CouponNotClaimableException;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CouponService.claim 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>신규 발급 성공</li>
 *   <li>코드 미존재 → CouponNotFoundException(404)</li>
 *   <li>비활성/기간외 → CouponNotClaimableException(400)</li>
 *   <li>UNIQUE 위반(DataIntegrityViolation mock) → CouponAlreadyOwnedException(409)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponServiceClaimTest {

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
    private static final String VALID_CODE = "SAVE10";

    private static final Instant STARTS = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant ENDS = Instant.parse("2027-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        couponService = new CouponService(couponRepository, userCouponRepository, cartCheckoutReader, productOrderCatalog);
    }

    @Test
    @DisplayName("신규 발급 성공 — UserCouponResult 반환")
    void claim_success_returnsUserCouponResult() {
        Coupon coupon = buildActiveCoupon(VALID_CODE);
        when(couponRepository.findByCode(VALID_CODE)).thenReturn(Optional.of(coupon));

        UserCoupon savedUc = buildUserCoupon(100L, USER_ID, coupon.getId());
        when(userCouponRepository.save(any())).thenReturn(savedUc);
        when(userCouponRepository.findByUserIdOrderByIssuedAtDesc(USER_ID)).thenReturn(java.util.List.of(savedUc));
        when(couponRepository.findAllById(any())).thenReturn(java.util.List.of(coupon));

        CouponService.UserCouponResult result = couponService.claim(USER_ID, VALID_CODE);

        assertThat(result.userCouponId()).isEqualTo(100L);
        assertThat(result.couponId()).isEqualTo(coupon.getId());
    }

    @Test
    @DisplayName("코드 미존재 → CouponNotFoundException(404)")
    void claim_codeNotFound_throwsCouponNotFoundException() {
        when(couponRepository.findByCode("NOTEXIST")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponService.claim(USER_ID, "NOTEXIST"))
                .isInstanceOf(CouponNotFoundException.class);
    }

    @Test
    @DisplayName("비활성 쿠폰 → CouponNotClaimableException(400)")
    void claim_inactiveCoupon_throwsCouponNotClaimableException() {
        Coupon inactive = Coupon.create(VALID_CODE, "비활성", "fixed", BigDecimal.ONE,
                BigDecimal.ZERO, null, STARTS, ENDS, null, false);
        when(couponRepository.findByCode(VALID_CODE)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> couponService.claim(USER_ID, VALID_CODE))
                .isInstanceOf(CouponNotClaimableException.class);
    }

    @Test
    @DisplayName("유효기간 이후 쿠폰 → CouponNotClaimableException(400)")
    void claim_expiredCoupon_throwsCouponNotClaimableException() {
        Instant pastStart = Instant.parse("2020-01-01T00:00:00Z");
        Instant pastEnd = Instant.parse("2020-12-31T23:59:59Z");
        Coupon expired = Coupon.create(VALID_CODE, "만료", "fixed", BigDecimal.ONE,
                BigDecimal.ZERO, null, pastStart, pastEnd, null, true);
        when(couponRepository.findByCode(VALID_CODE)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> couponService.claim(USER_ID, VALID_CODE))
                .isInstanceOf(CouponNotClaimableException.class);
    }

    @Test
    @DisplayName("UNIQUE 위반(DataIntegrityViolation) → CouponAlreadyOwnedException(409)")
    void claim_duplicateInsert_throwsCouponAlreadyOwnedException() {
        Coupon coupon = buildActiveCoupon(VALID_CODE);
        when(couponRepository.findByCode(VALID_CODE)).thenReturn(Optional.of(coupon));
        when(userCouponRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("UNIQUE constraint"));

        assertThatThrownBy(() -> couponService.claim(USER_ID, VALID_CODE))
                .isInstanceOf(CouponAlreadyOwnedException.class);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Coupon buildActiveCoupon(String code) {
        Coupon c = Coupon.create(code, "10% 할인", "percent", new BigDecimal("10"),
                BigDecimal.ZERO, null, STARTS, ENDS, null, true);
        setId(c, 99L);
        return c;
    }

    private UserCoupon buildUserCoupon(long id, long userId, long couponId) {
        UserCoupon uc = UserCoupon.issue(userId, couponId, Instant.now());
        setId(uc, id);
        return uc;
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
}
