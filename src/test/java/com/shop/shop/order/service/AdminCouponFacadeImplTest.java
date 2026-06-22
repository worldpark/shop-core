package com.shop.shop.order.service;

import com.shop.shop.order.domain.Coupon;
import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.repository.CouponRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminCouponFacadeImpl} 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>list(): CouponRepository.findAllByOrderByEndsAtDesc() 호출 + AdminCouponResponse 매핑</li>
 *   <li>list(): used_count / usage_limit / is_active 보존</li>
 *   <li>create(): CouponService.createDefinition 위임</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminCouponFacadeImplTest {

    @Mock
    private CouponService couponService;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponDtoMapper dtoMapper;

    private AdminCouponFacadeImpl adminCouponFacadeImpl;

    @BeforeEach
    void setUp() {
        adminCouponFacadeImpl = new AdminCouponFacadeImpl(couponService, couponRepository, dtoMapper);
    }

    @Test
    @DisplayName("list(): CouponRepository.findAllByOrderByEndsAtDesc() 호출")
    void list_callsFindAllByOrderByEndsAtDesc() {
        when(couponRepository.findAllByOrderByEndsAtDesc()).thenReturn(List.of());

        adminCouponFacadeImpl.list();

        verify(couponRepository).findAllByOrderByEndsAtDesc();
    }

    @Test
    @DisplayName("list(): 빈 결과 → 빈 목록 반환")
    void list_emptyCoupons_returnsEmptyList() {
        when(couponRepository.findAllByOrderByEndsAtDesc()).thenReturn(List.of());

        List<AdminCouponResponse> result = adminCouponFacadeImpl.list();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("list(): used_count / usage_limit / is_active 값 보존")
    void list_preservesUsedCountAndUsageLimitAndIsActive() {
        Coupon coupon = mockCoupon(5L, "SAVE500", "500원 할인", "fixed",
                new BigDecimal("500"), BigDecimal.ZERO, null,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                10, 3, true);

        when(couponRepository.findAllByOrderByEndsAtDesc()).thenReturn(List.of(coupon));

        List<AdminCouponResponse> result = adminCouponFacadeImpl.list();

        assertThat(result).hasSize(1);
        AdminCouponResponse resp = result.get(0);
        assertThat(resp.id()).isEqualTo(5L);
        assertThat(resp.code()).isEqualTo("SAVE500");
        assertThat(resp.usedCount()).isEqualTo(3);
        assertThat(resp.usageLimit()).isEqualTo(10);
        assertThat(resp.isActive()).isTrue();
    }

    @Test
    @DisplayName("list(): usageLimit=null(무제한)인 쿠폰도 정상 매핑")
    void list_unlimitedCoupon_usageLimitIsNull() {
        Coupon coupon = mockCoupon(6L, "FREE10", "10% 할인", "percent",
                new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("5000"),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, 0, true);

        when(couponRepository.findAllByOrderByEndsAtDesc()).thenReturn(List.of(coupon));

        List<AdminCouponResponse> result = adminCouponFacadeImpl.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).usageLimit()).isNull();
    }

    @Test
    @DisplayName("create(): CouponService.createDefinition 위임 + CouponDtoMapper.toAdminCouponResponse 반환")
    void create_delegatesToCouponServiceCreateDefinition() {
        AdminCouponCreateRequest req = new AdminCouponCreateRequest(
                "NEWCODE", "신규쿠폰", "fixed", new BigDecimal("1000"),
                BigDecimal.ZERO, null,
                Instant.now(), Instant.now().plusSeconds(86400),
                null, true
        );
        CouponService.CouponDefResult defResult = new CouponService.CouponDefResult(
                10L, "NEWCODE", "신규쿠폰", "fixed", new BigDecimal("1000"),
                BigDecimal.ZERO, null,
                Instant.now(), Instant.now().plusSeconds(86400),
                null, 0, true
        );
        AdminCouponResponse expectedResponse = new AdminCouponResponse(
                10L, "NEWCODE", "신규쿠폰", "fixed", new BigDecimal("1000"),
                BigDecimal.ZERO, null,
                Instant.now(), Instant.now().plusSeconds(86400),
                null, 0, true
        );

        when(couponService.createDefinition(req)).thenReturn(defResult);
        when(dtoMapper.toAdminCouponResponse(defResult)).thenReturn(expectedResponse);

        AdminCouponResponse result = adminCouponFacadeImpl.create(req);

        verify(couponService).createDefinition(req);
        verify(dtoMapper).toAdminCouponResponse(defResult);
        assertThat(result.code()).isEqualTo("NEWCODE");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    /**
     * Coupon Entity mock 생성 헬퍼 (Coupon은 protected 생성자라 Mockito mock 사용).
     */
    private Coupon mockCoupon(Long id, String code, String name, String discountType,
                               BigDecimal value, BigDecimal minOrderAmount, BigDecimal maxDiscount,
                               Instant startsAt, Instant endsAt,
                               Integer usageLimit, int usedCount, boolean isActive) {
        Coupon coupon = mock(Coupon.class);
        when(coupon.getId()).thenReturn(id);
        when(coupon.getCode()).thenReturn(code);
        when(coupon.getName()).thenReturn(name);
        when(coupon.getDiscountType()).thenReturn(discountType);
        when(coupon.getValue()).thenReturn(value);
        when(coupon.getMinOrderAmount()).thenReturn(minOrderAmount);
        when(coupon.getMaxDiscount()).thenReturn(maxDiscount);
        when(coupon.getStartsAt()).thenReturn(startsAt);
        when(coupon.getEndsAt()).thenReturn(endsAt);
        when(coupon.getUsageLimit()).thenReturn(usageLimit);
        when(coupon.getUsedCount()).thenReturn(usedCount);
        when(coupon.isActive()).thenReturn(isActive);
        return coupon;
    }
}
