package com.shop.shop.member.service;

import com.shop.shop.common.exception.SellerApplicationDuplicateException;
import com.shop.shop.common.exception.SellerApplicationNotFoundException;
import com.shop.shop.common.exception.SellerApplicationNotEligibleException;
import com.shop.shop.common.exception.SellerApplicationStateConflictException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.SellerApplication;
import com.shop.shop.member.domain.SellerApplicationStatus;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.SellerApplicationRequest;
import com.shop.shop.member.repository.SellerApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SellerApplicationService 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>신청 자격: CONSUMER → 성공, SELLER/ADMIN → 409 NotEligible</li>
 *   <li>PENDING 중복 사전 체크 → 409 Duplicate</li>
 *   <li>DataIntegrityViolationException 흡수 → 409 Duplicate</li>
 *   <li>승인: changeRole(.,SELLER) 1회 + 인자 검증 + status=APPROVED</li>
 *   <li>비-PENDING 승인 → 409 StateConflict (changeRole 미호출)</li>
 *   <li>승인 레이스(신청자 이미 SELLER/ADMIN) → 409 StateConflict</li>
 *   <li>반려: status=REJECTED + rejectReason + changeRole 미호출</li>
 *   <li>비-PENDING 반려 → 409 StateConflict</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerApplicationServiceTest {

    @Mock
    private SellerApplicationRepository sellerApplicationRepository;

    @Mock
    private MemberService memberService;

    private SellerApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SellerApplicationService(sellerApplicationRepository, memberService);
        TransactionSynchronizationManager.initSynchronization();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ============================================================
    // apply — 자격 검증
    // ============================================================

    @Test
    @DisplayName("apply — CONSUMER → 성공, repository.save 1회 호출")
    void apply_consumer_success_calls_save_once() {
        User consumer = userWithId(10L, "consumer@test.com", Role.CONSUMER);
        when(memberService.getById(10L)).thenReturn(consumer);
        when(sellerApplicationRepository.existsByUserIdAndStatus(10L, SellerApplicationStatus.PENDING))
                .thenReturn(false);

        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");
        SellerApplication saved = SellerApplication.submit(10L, "상호", "1234567890", "010-0000-0000");
        when(sellerApplicationRepository.save(any())).thenReturn(saved);

        SellerApplication result = service.apply(10L, req);

        verify(sellerApplicationRepository).save(any(SellerApplication.class));
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("apply — SELLER → SellerApplicationNotEligibleException(409), save 미호출")
    void apply_seller_throws_not_eligible_and_no_save() {
        User seller = userWithId(20L, "seller@test.com", Role.SELLER);
        when(memberService.getById(20L)).thenReturn(seller);

        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        assertThatThrownBy(() -> service.apply(20L, req))
                .isInstanceOf(SellerApplicationNotEligibleException.class);

        verify(sellerApplicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("apply — ADMIN → SellerApplicationNotEligibleException(409), save 미호출")
    void apply_admin_throws_not_eligible_and_no_save() {
        User admin = userWithId(1L, "admin@test.com", Role.ADMIN);
        when(memberService.getById(1L)).thenReturn(admin);

        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        assertThatThrownBy(() -> service.apply(1L, req))
                .isInstanceOf(SellerApplicationNotEligibleException.class);

        verify(sellerApplicationRepository, never()).save(any());
    }

    // ============================================================
    // apply — PENDING 중복
    // ============================================================

    @Test
    @DisplayName("apply — existsByUserIdAndStatus=true → SellerApplicationDuplicateException(409)")
    void apply_pending_exists_throws_duplicate() {
        User consumer = userWithId(10L, "consumer@test.com", Role.CONSUMER);
        when(memberService.getById(10L)).thenReturn(consumer);
        when(sellerApplicationRepository.existsByUserIdAndStatus(10L, SellerApplicationStatus.PENDING))
                .thenReturn(true);

        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        assertThatThrownBy(() -> service.apply(10L, req))
                .isInstanceOf(SellerApplicationDuplicateException.class);
    }

    @Test
    @DisplayName("apply — DataIntegrityViolationException 발생 시 SellerApplicationDuplicateException(409)으로 흡수")
    void apply_data_integrity_violation_absorbed_as_duplicate() {
        User consumer = userWithId(10L, "consumer@test.com", Role.CONSUMER);
        when(memberService.getById(10L)).thenReturn(consumer);
        when(sellerApplicationRepository.existsByUserIdAndStatus(10L, SellerApplicationStatus.PENDING))
                .thenReturn(false);
        when(sellerApplicationRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq violation"));

        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        assertThatThrownBy(() -> service.apply(10L, req))
                .isInstanceOf(SellerApplicationDuplicateException.class);
    }

    // ============================================================
    // approve — 성공 (changeRole 1회 + 인자 검증 + status=APPROVED)
    // ============================================================

    @Test
    @DisplayName("approve — PENDING + 신청자 CONSUMER → changeRole(reviewerId, applicantUserId, SELLER) 정확히 1회 호출")
    void approve_pending_consumer_calls_changeRole_with_correct_args() {
        long reviewerAdminId = 1L;
        long applicantUserId = 10L;
        long applicationId = 42L;

        SellerApplication app = pendingApp(applicationId, applicantUserId);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        User applicant = userWithId(applicantUserId, "consumer@test.com", Role.CONSUMER);
        when(memberService.getById(applicantUserId)).thenReturn(applicant);

        service.approve(reviewerAdminId, applicationId);

        ArgumentCaptor<Long> adminCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> targetCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(memberService).changeRole(adminCaptor.capture(), targetCaptor.capture(), roleCaptor.capture());

        assertThat(adminCaptor.getValue()).isEqualTo(reviewerAdminId);
        assertThat(targetCaptor.getValue()).isEqualTo(applicantUserId);
        assertThat(roleCaptor.getValue()).isEqualTo(Role.SELLER);

        assertThat(app.getStatus()).isEqualTo(SellerApplicationStatus.APPROVED);
        assertThat(app.getReviewedBy()).isEqualTo(reviewerAdminId);
        assertThat(app.getDecidedAt()).isNotNull();
    }

    // ============================================================
    // approve — 비-PENDING 거부
    // ============================================================

    @Test
    @DisplayName("approve — status=APPROVED → SellerApplicationStateConflictException(409), changeRole 미호출")
    void approve_already_approved_throws_state_conflict_no_changeRole() {
        long applicationId = 42L;
        SellerApplication app = approvedApp(applicationId, 10L);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.approve(1L, applicationId))
                .isInstanceOf(SellerApplicationStateConflictException.class);

        verify(memberService, never()).changeRole(any(Long.class), any(Long.class), any(Role.class));
    }

    @Test
    @DisplayName("approve — status=REJECTED → SellerApplicationStateConflictException(409), changeRole 미호출")
    void approve_rejected_throws_state_conflict_no_changeRole() {
        long applicationId = 43L;
        SellerApplication app = rejectedApp(applicationId, 10L);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.approve(1L, applicationId))
                .isInstanceOf(SellerApplicationStateConflictException.class);

        verify(memberService, never()).changeRole(any(Long.class), any(Long.class), any(Role.class));
    }

    // ============================================================
    // approve — 레이스(신청자 이미 SELLER/ADMIN)
    // ============================================================

    @Test
    @DisplayName("approve — PENDING + 신청자 현재 role=SELLER → SellerApplicationStateConflictException(409), changeRole 미호출")
    void approve_race_applicant_already_seller_throws_state_conflict() {
        long applicationId = 44L;
        long applicantUserId = 10L;
        SellerApplication app = pendingApp(applicationId, applicantUserId);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        User alreadySeller = userWithId(applicantUserId, "seller@test.com", Role.SELLER);
        when(memberService.getById(applicantUserId)).thenReturn(alreadySeller);

        assertThatThrownBy(() -> service.approve(1L, applicationId))
                .isInstanceOf(SellerApplicationStateConflictException.class)
                .hasMessageContaining("이미 판매자 이상 권한");

        verify(memberService, never()).changeRole(any(Long.class), any(Long.class), any(Role.class));
    }

    // ============================================================
    // approve — 신청 없음
    // ============================================================

    @Test
    @DisplayName("approve — 신청 없음 → SellerApplicationNotFoundException(404)")
    void approve_not_found_throws_not_found() {
        when(sellerApplicationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(1L, 999L))
                .isInstanceOf(SellerApplicationNotFoundException.class);
    }

    // ============================================================
    // reject — 성공
    // ============================================================

    @Test
    @DisplayName("reject — PENDING → status=REJECTED + rejectReason + changeRole 미호출")
    void reject_pending_transitions_to_rejected_no_changeRole() {
        long applicationId = 50L;
        long applicantUserId = 10L;
        SellerApplication app = pendingApp(applicationId, applicantUserId);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        service.reject(1L, applicationId, "서류 미비");

        assertThat(app.getStatus()).isEqualTo(SellerApplicationStatus.REJECTED);
        assertThat(app.getRejectReason()).isEqualTo("서류 미비");
        assertThat(app.getReviewedBy()).isEqualTo(1L);

        verify(memberService, never()).changeRole(any(Long.class), any(Long.class), any(Role.class));
    }

    // ============================================================
    // reject — 비-PENDING 거부
    // ============================================================

    @Test
    @DisplayName("reject — status=APPROVED → SellerApplicationStateConflictException(409)")
    void reject_already_approved_throws_state_conflict() {
        long applicationId = 51L;
        SellerApplication app = approvedApp(applicationId, 10L);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.reject(1L, applicationId, "사유"))
                .isInstanceOf(SellerApplicationStateConflictException.class);
    }

    // ============================================================
    // helpers
    // ============================================================

    private User userWithId(long id, String email, Role role) {
        User user = User.of(email, "hash", "이름", null, role);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }

    private SellerApplication pendingApp(long appId, long userId) {
        SellerApplication app = SellerApplication.submit(userId, "상호", "1234567890", "010-0000-0000");
        setId(app, appId);
        return app;
    }

    private SellerApplication approvedApp(long appId, long userId) {
        SellerApplication app = pendingApp(appId, userId);
        app.approve(1L);
        return app;
    }

    private SellerApplication rejectedApp(long appId, long userId) {
        SellerApplication app = pendingApp(appId, userId);
        app.reject(1L, "사유");
        return app;
    }

    private void setId(SellerApplication app, long id) {
        try {
            var idField = SellerApplication.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(app, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
