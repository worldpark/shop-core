package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.dto.UserCouponResponse;
import com.shop.shop.order.spi.CouponFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link CouponFacade} 구현체 (package-private).
 *
 * <p>order 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link CouponFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>email → userId 변환: {@link MemberDirectory#findUserIdByEmail(String)}</li>
 *   <li>CouponService 위임</li>
 *   <li>CouponDtoMapper를 통한 내부 결과 → DTO 변환</li>
 *   <li>Entity 미노출</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class CouponFacadeImpl implements CouponFacade {

    private final CouponService couponService;
    private final MemberDirectory memberDirectory;
    private final CouponDtoMapper dtoMapper;

    /**
     * {@inheritDoc}
     *
     * <p>email → userId(MemberDirectory) → CouponService.getMyCoupons(userId) 위임.
     */
    @Override
    @Transactional(readOnly = true)
    public List<UserCouponResponse> getMyWallet(String email) {
        long userId = memberDirectory.findUserIdByEmail(email);
        List<CouponService.UserCouponView> views = couponService.getMyCoupons(userId);
        return views.stream()
                .map(dtoMapper::toUserCouponResponse)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     *
     * <p>email → userId(MemberDirectory) → CouponService.claim(userId, code) 위임.
     * 반환값 없음 — View는 redirect 후 flash 메시지만 사용.
     */
    @Override
    @Transactional
    public void claim(String email, String code) {
        long userId = memberDirectory.findUserIdByEmail(email);
        couponService.claim(userId, code);
    }
}
