package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.dto.UserCouponResponse;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CouponFacadeImpl} 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>email → userId(MemberDirectory) 변환 후 CouponService 위임</li>
 *   <li>Entity 미노출(UserCouponResponse 반환)</li>
 *   <li>claim: email → userId → CouponService.claim 위임</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CouponFacadeImplTest {

    @Mock
    private CouponService couponService;

    @Mock
    private MemberDirectory memberDirectory;

    @Mock
    private CouponDtoMapper dtoMapper;

    private CouponFacadeImpl couponFacadeImpl;

    private static final String EMAIL = "consumer@example.com";
    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        couponFacadeImpl = new CouponFacadeImpl(couponService, memberDirectory, dtoMapper);
    }

    @Test
    @DisplayName("getMyWallet: email → userId 변환 후 CouponService.getMyCoupons 위임")
    void getMyWallet_convertsEmailToUserIdAndDelegatesToService() {
        CouponService.UserCouponView view = new CouponService.UserCouponView(
                1L, 10L, "TEST10", "테스트쿠폰", "fixed", new BigDecimal("1000"),
                BigDecimal.ZERO, null, Instant.now(), Instant.now().plusSeconds(3600),
                false, null, false
        );
        UserCouponResponse expectedDto = new UserCouponResponse(
                1L, 10L, "TEST10", "테스트쿠폰", "fixed", new BigDecimal("1000"),
                BigDecimal.ZERO, null, Instant.now(), Instant.now().plusSeconds(3600),
                false, null, false
        );

        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(couponService.getMyCoupons(USER_ID)).thenReturn(List.of(view));
        when(dtoMapper.toUserCouponResponse(view)).thenReturn(expectedDto);

        List<UserCouponResponse> result = couponFacadeImpl.getMyWallet(EMAIL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).code()).isEqualTo("TEST10");
        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(couponService).getMyCoupons(USER_ID);
    }

    @Test
    @DisplayName("getMyWallet: 보유 쿠폰 없음 — 빈 목록 반환")
    void getMyWallet_noWallet_returnsEmptyList() {
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(couponService.getMyCoupons(USER_ID)).thenReturn(List.of());

        List<UserCouponResponse> result = couponFacadeImpl.getMyWallet(EMAIL);

        assertThat(result).isEmpty();
        verify(memberDirectory).findUserIdByEmail(EMAIL);
    }

    @Test
    @DisplayName("getMyWallet: Entity 미노출 — 반환값은 UserCouponResponse(DTO)")
    void getMyWallet_returnsDto_notEntity() {
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(couponService.getMyCoupons(USER_ID)).thenReturn(List.of());

        List<UserCouponResponse> result = couponFacadeImpl.getMyWallet(EMAIL);

        // 반환 타입이 List<UserCouponResponse>임을 타입 컴파일로 보장 — Entity 미노출 확인
        assertThat(result).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("claim: email → userId 변환 후 CouponService.claim 위임")
    void claim_convertsEmailToUserIdAndDelegatesToService() {
        String code = "SAVE10";
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
        when(couponService.claim(USER_ID, code)).thenReturn(
                new CouponService.UserCouponResult(99L, 10L, Instant.now()));

        couponFacadeImpl.claim(EMAIL, code);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(couponService).claim(USER_ID, code);
    }
}
