package com.shop.shop.member.dto;

import com.shop.shop.member.domain.SellerApplication;

import java.time.Instant;

/**
 * 판매자 신청 응답 DTO (내 신청 / 단건 조회).
 *
 * <p>Entity를 직접 노출하지 않는다 (forbidden-rule).
 * 민감정보(password_hash/token/Redis 상태) 미포함.
 */
public record SellerApplicationResponse(
        long id,
        String status,
        String businessName,
        String businessRegistrationNumber,
        String contactPhone,
        String rejectReason,
        Instant createdAt,
        Instant decidedAt
) {

    /**
     * SellerApplication Entity → SellerApplicationResponse 변환.
     *
     * @param app Entity
     * @return DTO
     */
    public static SellerApplicationResponse from(SellerApplication app) {
        return new SellerApplicationResponse(
                app.getId(),
                app.getStatus().name(),
                app.getBusinessName(),
                app.getBusinessRegistrationNumber(),
                app.getContactPhone(),
                app.getRejectReason(),
                app.getCreatedAt(),
                app.getDecidedAt()
        );
    }
}
