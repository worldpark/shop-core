package com.shop.shop.member.repository;

import com.shop.shop.member.domain.SellerApplication;
import com.shop.shop.member.domain.SellerApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 판매자 신청 JPA 리포지토리.
 * 비즈니스 로직 없음 — SellerApplicationService에서만 호출.
 */
public interface SellerApplicationRepository extends JpaRepository<SellerApplication, Long> {

    /**
     * 특정 사용자의 특정 상태 신청 존재 여부.
     * PENDING 중복 신청 사전 체크에 사용.
     *
     * @param userId 사용자 ID
     * @param status 확인할 상태
     * @return 존재하면 true
     */
    boolean existsByUserIdAndStatus(long userId, SellerApplicationStatus status);

    /**
     * 특정 사용자의 가장 최근 신청 1건 (createdAt desc).
     * /me REST 응답 및 View /me 화면에 사용.
     *
     * @param userId 사용자 ID
     * @return 가장 최근 신청 (없으면 Optional.empty())
     */
    Optional<SellerApplication> findFirstByUserIdOrderByCreatedAtDesc(long userId);

    /**
     * 관리자 판매자 신청 목록 — 상태 필터(null=전체) + 페이지네이션.
     *
     * @param status   상태 필터 (null이면 전체)
     * @param pageable 페이지네이션
     * @return 조건에 맞는 신청 페이지
     */
    @Query("""
            select sa from SellerApplication sa
            where (:status is null or sa.status = :status)
            """)
    Page<SellerApplication> search(@Param("status") SellerApplicationStatus status, Pageable pageable);
}
