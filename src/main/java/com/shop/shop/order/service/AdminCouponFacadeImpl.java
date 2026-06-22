package com.shop.shop.order.service;

import com.shop.shop.order.domain.Coupon;
import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.spi.AdminCouponFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link AdminCouponFacade} 구현체 (package-private).
 *
 * <p>order 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link AdminCouponFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>list(): CouponRepository.findAllByOrderByEndsAtDesc() → AdminCouponResponse 매핑</li>
 *   <li>create(): CouponService.createDefinition(req) 위임 (031 그대로)</li>
 *   <li>Entity 미노출</li>
 * </ul>
 *
 * <p>CouponRepository를 직접 주입하는 것은 같은 order 모듈 internal이라 허용
 * (order/service가 order/repository 의존은 정상).
 */
@Service
@RequiredArgsConstructor
class AdminCouponFacadeImpl implements AdminCouponFacade {

    private final CouponService couponService;
    private final CouponRepository couponRepository;
    private final CouponDtoMapper dtoMapper;

    /**
     * {@inheritDoc}
     *
     * <p>CouponRepository.findAllByOrderByEndsAtDesc() → CouponDtoMapper.toAdminCouponResponse 매핑.
     * used_count / usage_limit / is_active는 Coupon Entity 필드 그대로 — 추가 집계 불필요.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AdminCouponResponse> list() {
        List<Coupon> coupons = couponRepository.findAllByOrderByEndsAtDesc();
        return coupons.stream()
                .map(coupon -> new AdminCouponResponse(
                        coupon.getId(),
                        coupon.getCode(),
                        coupon.getName(),
                        coupon.getDiscountType(),
                        coupon.getValue(),
                        coupon.getMinOrderAmount(),
                        coupon.getMaxDiscount(),
                        coupon.getStartsAt(),
                        coupon.getEndsAt(),
                        coupon.getUsageLimit(),
                        coupon.getUsedCount(),
                        coupon.isActive()
                ))
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     *
     * <p>CouponService.createDefinition(req) 위임 — 031 로직 무변경.
     */
    @Override
    @Transactional
    public AdminCouponResponse create(AdminCouponCreateRequest req) {
        CouponService.CouponDefResult result = couponService.createDefinition(req);
        return dtoMapper.toAdminCouponResponse(result);
    }
}
