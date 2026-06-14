package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import com.shop.shop.common.exception.CouponAlreadyOwnedException;
import com.shop.shop.common.exception.CouponConflictException;
import com.shop.shop.common.exception.CouponNotApplicableException;
import com.shop.shop.common.exception.CouponNotClaimableException;
import com.shop.shop.common.exception.CouponNotFoundException;
import com.shop.shop.common.exception.DuplicateCouponCodeException;
import com.shop.shop.order.domain.Coupon;
import com.shop.shop.order.domain.UserCoupon;
import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 쿠폰 도메인 서비스.
 *
 * <p>claim/조회/applicable 미리보기/적용 검증·계산/복원의 도메인 로직.
 * order 모듈 내부 서비스 — 다른 모듈에 노출하지 않는다.
 *
 * <p>소비/복원 UPDATE는 호출자 트랜잭션(@Transactional 전파 기본값 REQUIRED)에 합류한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final CartCheckoutReader cartCheckoutReader;
    private final ProductOrderCatalog productOrderCatalog;

    // ============================================================
    // 발급 (claim)
    // ============================================================

    /**
     * 쿠폰 코드로 발급.
     *
     * <p>활성·유효기간 내 쿠폰만 발급. used_count 미증가(사용 시점에 증가 — plan §1.5).
     *
     * @param userId 발급받을 userId
     * @param code   쿠폰 코드
     * @return 발급 결과
     * @throws CouponNotFoundException        코드 미존재
     * @throws CouponNotClaimableException    비활성/유효기간 외
     * @throws CouponAlreadyOwnedException    1인 1매 중복 발급
     */
    @Transactional
    public UserCouponResult claim(long userId, String code) {
        Instant now = Instant.now();

        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(CouponNotFoundException::new);

        if (!coupon.isClaimable(now)) {
            throw new CouponNotClaimableException();
        }

        try {
            UserCoupon saved = userCouponRepository.save(UserCoupon.issue(userId, coupon.getId(), now));
            return new UserCouponResult(saved.getId(), coupon.getId(), saved.getIssuedAt());
        } catch (DataIntegrityViolationException e) {
            throw new CouponAlreadyOwnedException();
        }
    }

    // ============================================================
    // 조회
    // ============================================================

    /**
     * 내 쿠폰함 조회 (미사용/사용/만료 구분).
     */
    @Transactional(readOnly = true)
    public List<UserCouponView> getMyCoupons(long userId) {
        Instant now = Instant.now();
        List<UserCoupon> userCoupons = userCouponRepository.findByUserIdOrderByIssuedAtDesc(userId);

        if (userCoupons.isEmpty()) {
            return List.of();
        }

        List<Long> couponIds = userCoupons.stream()
                .map(UserCoupon::getCouponId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Coupon> couponById = couponRepository.findAllById(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, Function.identity()));

        List<UserCouponView> result = new ArrayList<>();
        for (UserCoupon uc : userCoupons) {
            Coupon coupon = couponById.get(uc.getCouponId());
            if (coupon == null) {
                continue; // 삭제된 쿠폰 — 스킵
            }
            boolean used = uc.isUsed();
            boolean expired = !now.isBefore(coupon.getEndsAt());
            result.add(new UserCouponView(
                    uc.getId(),
                    coupon.getId(),
                    coupon.getCode(),
                    coupon.getName(),
                    coupon.getDiscountType(),
                    coupon.getValue(),
                    coupon.getMinOrderAmount(),
                    coupon.getMaxDiscount(),
                    coupon.getStartsAt(),
                    coupon.getEndsAt(),
                    used,
                    uc.getUsedAt(),
                    expired
            ));
        }
        return result;
    }

    // ============================================================
    // 적용 미리보기
    // ============================================================

    /**
     * 현재 장바구니 기준 적용 가능 쿠폰 미리보기.
     *
     * <p>읽기 전용 — 상태 변경 없음.
     */
    @Transactional(readOnly = true)
    public List<ApplicableCouponView> getApplicable(long userId) {
        Instant now = Instant.now();

        // 장바구니 itemsAmount 산정
        BigDecimal itemsAmount = computeCartItemsAmount(userId);

        // 미사용 보유 쿠폰
        List<UserCoupon> unusedUserCoupons = userCouponRepository.findByUserIdAndUsedAtIsNull(userId);
        if (unusedUserCoupons.isEmpty()) {
            return List.of();
        }

        List<Long> couponIds = unusedUserCoupons.stream()
                .map(UserCoupon::getCouponId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Coupon> couponById = couponRepository.findAllById(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, Function.identity()));

        List<ApplicableCouponView> result = new ArrayList<>();
        for (UserCoupon uc : unusedUserCoupons) {
            Coupon coupon = couponById.get(uc.getCouponId());
            if (coupon == null) {
                continue;
            }

            if (itemsAmount.compareTo(BigDecimal.ZERO) == 0) {
                result.add(new ApplicableCouponView(
                        uc.getId(), coupon.getId(), coupon.getCode(), coupon.getName(),
                        false, BigDecimal.ZERO, "장바구니가 비어 있습니다."));
                continue;
            }

            if (!coupon.isActive()) {
                result.add(new ApplicableCouponView(
                        uc.getId(), coupon.getId(), coupon.getCode(), coupon.getName(),
                        false, BigDecimal.ZERO, "비활성 쿠폰입니다."));
                continue;
            }

            if (!coupon.isWithinPeriod(now)) {
                result.add(new ApplicableCouponView(
                        uc.getId(), coupon.getId(), coupon.getCode(), coupon.getName(),
                        false, BigDecimal.ZERO, "사용 기간이 아닌 쿠폰입니다."));
                continue;
            }

            if (!coupon.meetsMinOrder(itemsAmount)) {
                result.add(new ApplicableCouponView(
                        uc.getId(), coupon.getId(), coupon.getCode(), coupon.getName(),
                        false, BigDecimal.ZERO,
                        "최소 주문금액(" + coupon.getMinOrderAmount() + "원) 미달입니다."));
                continue;
            }

            BigDecimal expectedDiscount = coupon.calculateDiscount(itemsAmount);
            result.add(new ApplicableCouponView(
                    uc.getId(), coupon.getId(), coupon.getCode(), coupon.getName(),
                    true, expectedDiscount, null));
        }
        return result;
    }

    // ============================================================
    // 주문 적용 — 2단계 (computeDiscount / consume)
    // ============================================================

    /**
     * 할인액 산정 (order 생성 전 검증 + 계산).
     *
     * <p>소유권·미사용·활성·유효기간·최소주문금액 검증 포함.
     * 호출자 트랜잭션 안에서 실행된다.
     *
     * @param userId         소유자 userId
     * @param userCouponId   user_coupon id
     * @param itemsAmount    상품 합계금액
     * @return 할인 결과 (discountAmount, couponId)
     */
    public AppliedDiscount computeDiscount(long userId, long userCouponId, BigDecimal itemsAmount) {
        Instant now = Instant.now();

        UserCoupon userCoupon = userCouponRepository.findByIdAndUserId(userCouponId, userId)
                .orElseThrow(CouponNotFoundException::new);

        if (userCoupon.isUsed()) {
            throw new CouponConflictException("이미 사용된 쿠폰입니다.");
        }

        Coupon coupon = couponRepository.findById(userCoupon.getCouponId())
                .orElseThrow(CouponNotFoundException::new);

        if (!coupon.isActive() || !coupon.isWithinPeriod(now)) {
            throw new CouponNotApplicableException("사용 기간이 아니거나 비활성 쿠폰입니다.");
        }

        if (!coupon.meetsMinOrder(itemsAmount)) {
            throw new CouponNotApplicableException("최소 주문금액을 충족하지 않습니다.");
        }

        BigDecimal discountAmount = coupon.calculateDiscount(itemsAmount);
        return new AppliedDiscount(discountAmount, coupon.getId());
    }

    /**
     * 쿠폰 소비 — 조건부 UPDATE 2건 (order 저장 후).
     *
     * <p>markUsedIfUnused → 영향행 0이면 동시 사용 경합 패자 → CouponConflictException(롤백).
     * incrementUsedCountIfWithinLimit → 영향행 0이면 한도 소진/비활성 → CouponConflictException(롤백).
     * 호출자 트랜잭션 안에서 실행된다.
     *
     * @param userCouponId user_coupon id
     * @param userId       소유자 userId
     * @param couponId     쿠폰 id
     * @param orderId      연결할 주문 id
     * @param now          사용 시각
     */
    public void consume(long userCouponId, long userId, long couponId, long orderId, Instant now) {
        int marked = userCouponRepository.markUsedIfUnused(userCouponId, userId, orderId, now);
        if (marked == 0) {
            throw new CouponConflictException("쿠폰을 사용할 수 없습니다.");
        }

        int incremented = couponRepository.incrementUsedCountIfWithinLimit(couponId);
        if (incremented == 0) {
            throw new CouponConflictException("쿠폰 사용 한도가 마감되었습니다.");
        }
    }

    // ============================================================
    // 복원 (취소·만료)
    // ============================================================

    /**
     * 주문 취소·만료 시 쿠폰 복원 (멱등).
     *
     * <p>findByOrderId로 couponId 확보 → restoreByOrderId(user_coupon) → decrementUsedCount(coupon).
     * 쿠폰 미적용 주문이거나 이미 복원된 경우 영향행 0 → no-op.
     * 호출자 트랜잭션 안에서 실행된다.
     *
     * @param orderId 복원할 주문 id
     */
    public void restoreByOrder(long orderId) {
        userCouponRepository.findByOrderId(orderId).ifPresent(userCoupon -> {
            long couponId = userCoupon.getCouponId();
            int restored = userCouponRepository.restoreByOrderId(orderId);
            if (restored > 0) {
                couponRepository.decrementUsedCount(couponId);
                log.info("쿠폰 복원: orderId={}, couponId={}", orderId, couponId);
            } else {
                log.debug("쿠폰 복원 no-op(이미 복원됨): orderId={}", orderId);
            }
        });
    }

    // ============================================================
    // Admin — 쿠폰 정의 생성
    // ============================================================

    /**
     * 쿠폰 정의 생성 (ADMIN).
     *
     * @param req 생성 요청
     * @return 생성 결과
     * @throws DuplicateCouponCodeException 코드 중복
     */
    @Transactional
    public CouponDefResult createDefinition(AdminCouponCreateRequest req) {
        boolean isActive = (req.isActive() != null) ? req.isActive() : true;
        Coupon coupon = Coupon.create(
                req.code(), req.name(), req.discountType(), req.value(),
                req.minOrderAmount(), req.maxDiscount(),
                req.startsAt(), req.endsAt(),
                req.usageLimit(), isActive
        );

        try {
            Coupon saved = couponRepository.save(coupon);
            return new CouponDefResult(
                    saved.getId(), saved.getCode(), saved.getName(),
                    saved.getDiscountType(), saved.getValue(),
                    saved.getMinOrderAmount(), saved.getMaxDiscount(),
                    saved.getStartsAt(), saved.getEndsAt(),
                    saved.getUsageLimit(), saved.getUsedCount(), saved.isActive()
            );
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateCouponCodeException();
        }
    }

    // ============================================================
    // 내부 헬퍼
    // ============================================================

    private BigDecimal computeCartItemsAmount(long userId) {
        CartCheckout checkout = cartCheckoutReader.getCheckoutCart(userId);
        if (checkout.items().isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Long> variantIds = checkout.items().stream()
                .map(CartCheckoutItem::variantId)
                .collect(Collectors.toList());

        List<OrderableVariantSnapshot> snapshots = productOrderCatalog.getOrderableSnapshots(variantIds);
        Map<Long, OrderableVariantSnapshot> snapshotByVariantId = snapshots.stream()
                .collect(Collectors.toMap(OrderableVariantSnapshot::variantId, Function.identity()));

        return checkout.items().stream()
                .map(item -> {
                    OrderableVariantSnapshot snap = snapshotByVariantId.get(item.variantId());
                    if (snap == null) {
                        return BigDecimal.ZERO;
                    }
                    return snap.price().multiply(BigDecimal.valueOf(item.quantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ============================================================
    // 내부 결과 record (Entity 미노출)
    // ============================================================

    public record UserCouponResult(long userCouponId, long couponId, Instant issuedAt) {}

    public record UserCouponView(
            long userCouponId,
            long couponId,
            String code,
            String name,
            String discountType,
            BigDecimal value,
            BigDecimal minOrderAmount,
            BigDecimal maxDiscount,
            Instant startsAt,
            Instant endsAt,
            boolean used,
            Instant usedAt,
            boolean expired
    ) {}

    public record ApplicableCouponView(
            long userCouponId,
            long couponId,
            String code,
            String name,
            boolean applicable,
            BigDecimal expectedDiscount,
            String reason
    ) {}

    public record AppliedDiscount(BigDecimal discountAmount, long couponId) {}

    public record CouponDefResult(
            Long id,
            String code,
            String name,
            String discountType,
            BigDecimal value,
            BigDecimal minOrderAmount,
            BigDecimal maxDiscount,
            Instant startsAt,
            Instant endsAt,
            Integer usageLimit,
            int usedCount,
            boolean isActive
    ) {}
}
