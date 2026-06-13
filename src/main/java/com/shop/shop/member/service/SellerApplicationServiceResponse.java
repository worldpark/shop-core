package com.shop.shop.member.service;

import com.shop.shop.member.dto.SellerApplicationRequest;
import com.shop.shop.member.dto.SellerApplicationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 판매자 신청 REST 응답 조합 전용 ServiceResponse (consumer REST).
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link SellerApplicationService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다 (forbidden-rule).
 *
 * <p>레이어: SellerApplicationRestController → SellerApplicationServiceResponse → SellerApplicationService
 * <p>principal 규약: JWT 기반 REST = userId(long), {@code (long) auth.getPrincipal()} (006/008 규약).
 */
@Service
@RequiredArgsConstructor
public class SellerApplicationServiceResponse {

    private final SellerApplicationService sellerApplicationService;

    /**
     * 판매자 신청 제출 — REST 전용.
     *
     * @param auth 인증 객체 (principal = userId long)
     * @param req  신청 요청 DTO
     * @return 신청 응답 DTO (201 Created용)
     */
    public SellerApplicationResponse apply(Authentication auth, SellerApplicationRequest req) {
        long uid = (long) auth.getPrincipal();
        return SellerApplicationResponse.from(sellerApplicationService.apply(uid, req));
    }

    /**
     * 내 신청 조회 — REST 전용 (없으면 서비스가 404 throw).
     *
     * @param auth 인증 객체 (principal = userId long)
     * @return 신청 응답 DTO
     */
    public SellerApplicationResponse me(Authentication auth) {
        long uid = (long) auth.getPrincipal();
        return SellerApplicationResponse.from(sellerApplicationService.getMyLatest(uid));
    }
}
