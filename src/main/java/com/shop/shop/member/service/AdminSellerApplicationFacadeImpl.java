package com.shop.shop.member.service;

import com.shop.shop.member.domain.SellerApplicationStatus;
import com.shop.shop.member.dto.SellerApplicationSummaryResponse;
import com.shop.shop.member.spi.AdminSellerApplicationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * {@link AdminSellerApplicationFacade} 구현체.
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link AdminSellerApplicationFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>status(String) → {@link SellerApplicationStatus} 변환 (null/빈 문자열 = null = 전체)</li>
 *   <li>adminEmail → adminUserId 해석 ({@link MemberService#getByEmail(String)})</li>
 *   <li>{@link com.shop.shop.member.domain.SellerApplication} Entity → {@link SellerApplicationSummaryResponse} DTO 매핑</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class AdminSellerApplicationFacadeImpl implements AdminSellerApplicationFacade {

    private final SellerApplicationService sellerApplicationService;
    private final MemberService memberService;

    /**
     * {@inheritDoc}
     *
     * <p>status(String) → {@link SellerApplicationStatus}로 변환 후 {@link SellerApplicationService#search}에 위임.
     * null/빈 문자열은 null(= 전체 조회)로 처리한다.
     */
    @Override
    public Page<SellerApplicationSummaryResponse> search(String status, int page, int size) {
        SellerApplicationStatus statusEnum = toStatusOrNull(status);
        return sellerApplicationService.search(statusEnum, PageRequest.of(page, size))
                .map(SellerApplicationSummaryResponse::from);
    }

    /**
     * {@inheritDoc}
     *
     * <p>처리 순서:
     * <ol>
     *   <li>adminEmail → adminUserId: {@link MemberService#getByEmail(String)}.getId()</li>
     *   <li>{@link SellerApplicationService#approve(long, long)} 위임</li>
     * </ol>
     * BusinessException은 변환 없이 그대로 전파한다.
     */
    @Override
    public void approve(String adminEmail, long applicationId) {
        long adminUserId = memberService.getByEmail(adminEmail).getId();
        sellerApplicationService.approve(adminUserId, applicationId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>처리 순서:
     * <ol>
     *   <li>adminEmail → adminUserId: {@link MemberService#getByEmail(String)}.getId()</li>
     *   <li>{@link SellerApplicationService#reject(long, long, String)} 위임</li>
     * </ol>
     * BusinessException은 변환 없이 그대로 전파한다.
     */
    @Override
    public void reject(String adminEmail, long applicationId, String reason) {
        long adminUserId = memberService.getByEmail(adminEmail).getId();
        sellerApplicationService.reject(adminUserId, applicationId, reason);
    }

    /**
     * status 문자열 → {@link SellerApplicationStatus} 변환.
     * null 또는 빈 문자열이면 null 반환 (= 전체 조회 필터).
     */
    private SellerApplicationStatus toStatusOrNull(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        return SellerApplicationStatus.valueOf(status);
    }
}
