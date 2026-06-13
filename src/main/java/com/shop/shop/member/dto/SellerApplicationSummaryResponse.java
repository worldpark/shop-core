package com.shop.shop.member.dto;

import com.shop.shop.member.domain.SellerApplication;

import java.time.Instant;

/**
 * 판매자 신청 목록 항목 DTO (관리자 심사 목록).
 *
 * <p>Entity를 직접 노출하지 않는다 (forbidden-rule).
 * 신청자 식별은 userId만 포함 (민감정보 최소화 — email 조인 없음, Task §1.6).
 */
public record SellerApplicationSummaryResponse(
        long id,
        long userId,
        String status,
        String businessName,
        String businessRegistrationNumber,
        String contactPhone,
        Instant createdAt,
        Instant decidedAt
) {

    /**
     * SellerApplication Entity → SellerApplicationSummaryResponse 변환.
     *
     * @param app Entity
     * @return DTO
     */
    public static SellerApplicationSummaryResponse from(SellerApplication app) {
        return new SellerApplicationSummaryResponse(
                app.getId(),
                app.getUserId(),
                app.getStatus().name(),
                app.getBusinessName(),
                app.getBusinessRegistrationNumber(),
                app.getContactPhone(),
                app.getCreatedAt(),
                app.getDecidedAt()
        );
    }
}
