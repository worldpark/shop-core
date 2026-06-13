package com.shop.shop.member.service;

import com.shop.shop.common.exception.SellerApplicationDuplicateException;
import com.shop.shop.common.exception.SellerApplicationNotFoundException;
import com.shop.shop.common.exception.SellerApplicationNotEligibleException;
import com.shop.shop.common.exception.SellerApplicationStateConflictException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.SellerApplication;
import com.shop.shop.member.domain.SellerApplicationStatus;
import com.shop.shop.member.dto.SellerApplicationRequest;
import com.shop.shop.member.repository.SellerApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 판매자 신청 도메인 서비스.
 *
 * <p>신청/승인/반려/조회 로직을 단일 소유한다.
 * Repository와 MemberService는 이 클래스에서만 호출한다.
 *
 * <p>설계 핵심 — 인가 ≠ 자격:
 * <ul>
 *   <li>보안 floor는 {@code authenticated}. "CONSUMER만" 규칙은 도메인 자격으로 검증 → 409.</li>
 *   <li>승인은 008 {@link MemberService#changeRole}를 단일 트랜잭션 안에서 호출(합류)한다.</li>
 *   <li>중복 차단: 사전 체크 + DB 부분 유니크 인덱스 위반 흡수(§1.5).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerApplicationService {

    private final SellerApplicationRepository sellerApplicationRepository;
    private final MemberService memberService;

    /**
     * 판매자 신청 제출.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>신청자 현재 role 확인 — CONSUMER가 아니면 {@link SellerApplicationNotEligibleException}(409)</li>
     *   <li>PENDING 중복 사전 체크 — 존재하면 {@link SellerApplicationDuplicateException}(409)</li>
     *   <li>save({@link SellerApplication#submit}) — 경합 DataIntegrityViolationException 흡수 → 409</li>
     * </ol>
     *
     * @param applicantUserId 신청자 userId
     * @param req             신청 요청 DTO
     * @return 저장된 신청 Entity
     * @throws SellerApplicationNotEligibleException SELLER/ADMIN이 신청 시도 (409)
     * @throws SellerApplicationDuplicateException   PENDING 중복 (409)
     */
    @Transactional
    public SellerApplication apply(long applicantUserId, SellerApplicationRequest req) {
        // 1. 현재 role 확인 — CONSUMER가 아니면 자격 미달(409)
        Role currentRole = memberService.getById(applicantUserId).getRole();
        if (currentRole != Role.CONSUMER) {
            throw new SellerApplicationNotEligibleException();
        }

        // 2. PENDING 중복 사전 체크 (정상 경로 빠른 실패)
        if (sellerApplicationRepository.existsByUserIdAndStatus(applicantUserId, SellerApplicationStatus.PENDING)) {
            throw new SellerApplicationDuplicateException();
        }

        // 3. INSERT — 경합 시 DataIntegrityViolationException 흡수(§1.5)
        try {
            SellerApplication saved = sellerApplicationRepository.save(
                    SellerApplication.submit(
                            applicantUserId,
                            req.businessName(),
                            req.businessRegistrationNumber(),
                            req.contactPhone()
                    )
            );
            log.info("판매자 신청 제출: userId={}, applicationId={}", applicantUserId, saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 동시성 경합: uq_seller_application_pending 위반 흡수
            throw new SellerApplicationDuplicateException();
        }
    }

    /**
     * 본인 최신 신청 조회 — REST /me 전용 (없으면 404).
     *
     * @param userId 사용자 userId
     * @return 가장 최근 신청 Entity
     * @throws SellerApplicationNotFoundException 이력 없음 (404)
     */
    @Transactional(readOnly = true)
    public SellerApplication getMyLatest(long userId) {
        return sellerApplicationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(SellerApplicationNotFoundException::new);
    }

    /**
     * 본인 최신 신청 Optional 조회 — View /me 전용 (없으면 빈 Optional).
     *
     * <p>View는 404 에러 페이지보다 "아직 신청 내역 없음 + 신청 링크" 안내가 UX상 자연스럽다(§1.7).
     *
     * @param userId 사용자 userId
     * @return 가장 최근 신청 Optional (없으면 empty)
     */
    @Transactional(readOnly = true)
    public Optional<SellerApplication> findMyLatest(long userId) {
        return sellerApplicationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 관리자 판매자 신청 목록 조회 — 상태 필터(null=전체) + 페이지네이션.
     *
     * @param status   상태 필터 (null=전체)
     * @param pageable 페이지네이션
     * @return 조건에 맞는 신청 페이지
     */
    @Transactional(readOnly = true)
    public Page<SellerApplication> search(SellerApplicationStatus status, PageRequest pageable) {
        return sellerApplicationRepository.search(status, pageable);
    }

    /**
     * 판매자 신청 승인 — 단일 트랜잭션 안에서 008 changeRole(SELLER) 합류 호출.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>신청 조회 — 없으면 {@link SellerApplicationNotFoundException}(404)</li>
     *   <li>status != PENDING → {@link SellerApplicationStateConflictException}(409) (비-PENDING 거부)</li>
     *   <li>신청자 현재 role 재확인 — CONSUMER 아니면 409 (레이스 방어)</li>
     *   <li>{@link MemberService#changeRole}(reviewerAdminId, applicantUserId, SELLER) — 단일 TX 합류 승격</li>
     *   <li>{@link SellerApplication#approve}(reviewerAdminId) — dirty checking UPDATE</li>
     * </ol>
     *
     * @param reviewerAdminId 심사 ADMIN userId (JWT principal)
     * @param applicationId   신청 ID
     * @throws SellerApplicationNotFoundException    신청 없음 (404)
     * @throws SellerApplicationStateConflictException 비-PENDING 또는 레이스 (409)
     */
    @Transactional
    public void approve(long reviewerAdminId, long applicationId) {
        // 1. 신청 조회
        SellerApplication app = sellerApplicationRepository.findById(applicationId)
                .orElseThrow(SellerApplicationNotFoundException::new);

        // 2. 비-PENDING 상태 → 409 (터미널 재심사 거부 — §1.4)
        if (app.getStatus() != SellerApplicationStatus.PENDING) {
            throw new SellerApplicationStateConflictException(
                    "PENDING 상태의 신청만 승인할 수 있습니다. 현재 상태: " + app.getStatus().name());
        }

        // 3. 신청자 현재 role 재확인 — 레이스(admin 토글로 이미 SELLER/ADMIN) 방어
        Role applicantRole = memberService.getById(app.getUserId()).getRole();
        if (applicantRole != Role.CONSUMER) {
            throw new SellerApplicationStateConflictException("신청자가 이미 판매자 이상 권한입니다.");
        }

        // 4. 008 changeRole 재사용 — 단일 트랜잭션 합류(Propagation.REQUIRED)
        //    afterCommit에 deleteRefresh 등록 → 외곽 커밋 후 1회 실행(refresh 무효화)
        memberService.changeRole(reviewerAdminId, app.getUserId(), Role.SELLER);

        // 5. 신청 상태 전이 (dirty checking UPDATE)
        app.approve(reviewerAdminId);

        log.info("판매자 신청 승인: reviewerId={}, applicationId={}, applicantUserId={}",
                reviewerAdminId, applicationId, app.getUserId());
    }

    /**
     * 판매자 신청 반려 — role 변경 없음, 사유 기록.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>신청 조회 — 없으면 404</li>
     *   <li>status != PENDING → 409</li>
     *   <li>{@link SellerApplication#reject}(reviewerAdminId, reason) — dirty checking UPDATE</li>
     * </ol>
     *
     * @param reviewerAdminId 심사 ADMIN userId
     * @param applicationId   신청 ID
     * @param reason          반려 사유
     * @throws SellerApplicationNotFoundException    신청 없음 (404)
     * @throws SellerApplicationStateConflictException 비-PENDING (409)
     */
    @Transactional
    public void reject(long reviewerAdminId, long applicationId, String reason) {
        // 1. 신청 조회
        SellerApplication app = sellerApplicationRepository.findById(applicationId)
                .orElseThrow(SellerApplicationNotFoundException::new);

        // 2. 비-PENDING → 409
        if (app.getStatus() != SellerApplicationStatus.PENDING) {
            throw new SellerApplicationStateConflictException(
                    "PENDING 상태의 신청만 반려할 수 있습니다. 현재 상태: " + app.getStatus().name());
        }

        // 3. 반려 처리 (role 변경 없음)
        app.reject(reviewerAdminId, reason);

        log.info("판매자 신청 반려: reviewerId={}, applicationId={}, applicantUserId={}",
                reviewerAdminId, applicationId, app.getUserId());
    }
}
