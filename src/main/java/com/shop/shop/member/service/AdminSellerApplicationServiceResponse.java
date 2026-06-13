package com.shop.shop.member.service;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.member.domain.SellerApplicationStatus;
import com.shop.shop.member.dto.RejectRequest;
import com.shop.shop.member.dto.SellerApplicationSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 관리자 판매자 신청 REST 응답 조합 전용 ServiceResponse (admin REST).
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link SellerApplicationService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다 (forbidden-rule).
 *
 * <p>레이어: AdminSellerApplicationRestController → AdminSellerApplicationServiceResponse → SellerApplicationService
 * <p>principal 규약: JWT 기반 REST = userId(long), {@code (long) auth.getPrincipal()} (006/008 규약).
 */
@Service
@RequiredArgsConstructor
public class AdminSellerApplicationServiceResponse {

    private final SellerApplicationService sellerApplicationService;

    /**
     * 관리자 판매자 신청 목록 조회 — REST 전용.
     *
     * @param status status 필터 문자열 (null/빈 문자열 = 전체)
     * @param page   페이지 번호 (0 기반)
     * @param size   페이지 크기
     * @return PageResponse&lt;SellerApplicationSummaryResponse&gt;
     */
    public PageResponse<SellerApplicationSummaryResponse> list(String status, int page, int size) {
        SellerApplicationStatus statusEnum = toStatusOrNull(status);
        return PageResponse.of(
                sellerApplicationService.search(statusEnum, PageRequest.of(page, size))
                        .map(SellerApplicationSummaryResponse::from)
        );
    }

    /**
     * 판매자 신청 승인 — REST 전용.
     *
     * @param auth 인증 객체 (principal = adminUserId long)
     * @param id   신청 ID
     */
    public void approve(Authentication auth, long id) {
        long reviewerAdminId = (long) auth.getPrincipal();
        sellerApplicationService.approve(reviewerAdminId, id);
    }

    /**
     * 판매자 신청 반려 — REST 전용.
     *
     * @param auth 인증 객체 (principal = adminUserId long)
     * @param id   신청 ID
     * @param req  반려 요청 DTO
     */
    public void reject(Authentication auth, long id, RejectRequest req) {
        long reviewerAdminId = (long) auth.getPrincipal();
        sellerApplicationService.reject(reviewerAdminId, id, req.reason());
    }

    /**
     * status 문자열 → {@link SellerApplicationStatus} 변환.
     * null 또는 빈 문자열이면 null 반환 (= 전체 조회).
     */
    private SellerApplicationStatus toStatusOrNull(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        return SellerApplicationStatus.valueOf(status);
    }
}
