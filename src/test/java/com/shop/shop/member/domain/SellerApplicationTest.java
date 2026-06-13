package com.shop.shop.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SellerApplication 도메인 상태 전이 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>submit → PENDING + 필드 세팅</li>
 *   <li>approve → APPROVED + reviewedBy + decidedAt</li>
 *   <li>reject → REJECTED + rejectReason + reviewedBy + decidedAt</li>
 *   <li>터미널 상태 재전이 가드 (approve 후 reject 시 IllegalStateException)</li>
 * </ul>
 */
class SellerApplicationTest {

    // ============================================================
    // submit
    // ============================================================

    @Test
    @DisplayName("submit → status=PENDING + 필드 세팅")
    void submit_creates_pending_application_with_fields() {
        SellerApplication app = SellerApplication.submit(
                100L, "테스트상회", "1234567890", "010-1234-5678");

        assertThat(app.getUserId()).isEqualTo(100L);
        assertThat(app.getStatus()).isEqualTo(SellerApplicationStatus.PENDING);
        assertThat(app.getBusinessName()).isEqualTo("테스트상회");
        assertThat(app.getBusinessRegistrationNumber()).isEqualTo("1234567890");
        assertThat(app.getContactPhone()).isEqualTo("010-1234-5678");
        assertThat(app.getRejectReason()).isNull();
        assertThat(app.getReviewedBy()).isNull();
        assertThat(app.getDecidedAt()).isNull();
    }

    // ============================================================
    // approve
    // ============================================================

    @Test
    @DisplayName("approve → status=APPROVED + reviewedBy + decidedAt 세팅")
    void approve_transitions_to_approved_with_reviewer_and_decided_at() {
        SellerApplication app = SellerApplication.submit(
                100L, "테스트상회", "1234567890", "010-1234-5678");

        app.approve(1L);

        assertThat(app.getStatus()).isEqualTo(SellerApplicationStatus.APPROVED);
        assertThat(app.getReviewedBy()).isEqualTo(1L);
        assertThat(app.getDecidedAt()).isNotNull();
    }

    // ============================================================
    // reject
    // ============================================================

    @Test
    @DisplayName("reject → status=REJECTED + rejectReason + reviewedBy + decidedAt 세팅")
    void reject_transitions_to_rejected_with_reason_and_reviewer() {
        SellerApplication app = SellerApplication.submit(
                100L, "테스트상회", "1234567890", "010-1234-5678");

        app.reject(2L, "서류 미비");

        assertThat(app.getStatus()).isEqualTo(SellerApplicationStatus.REJECTED);
        assertThat(app.getRejectReason()).isEqualTo("서류 미비");
        assertThat(app.getReviewedBy()).isEqualTo(2L);
        assertThat(app.getDecidedAt()).isNotNull();
    }

    // ============================================================
    // 터미널 상태 재전이 가드
    // ============================================================

    @Test
    @DisplayName("approve 후 approve 재시도 → IllegalStateException (터미널 재전이 가드)")
    void approve_on_approved_application_throws_illegal_state() {
        SellerApplication app = SellerApplication.submit(
                100L, "테스트상회", "1234567890", "010-1234-5678");
        app.approve(1L);

        assertThatThrownBy(() -> app.approve(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("approve 후 reject 재시도 → IllegalStateException (터미널 재전이 가드)")
    void reject_on_approved_application_throws_illegal_state() {
        SellerApplication app = SellerApplication.submit(
                100L, "테스트상회", "1234567890", "010-1234-5678");
        app.approve(1L);

        assertThatThrownBy(() -> app.reject(1L, "취소 불가"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("reject 후 approve 재시도 → IllegalStateException (터미널 재전이 가드)")
    void approve_on_rejected_application_throws_illegal_state() {
        SellerApplication app = SellerApplication.submit(
                100L, "테스트상회", "1234567890", "010-1234-5678");
        app.reject(1L, "서류 미비");

        assertThatThrownBy(() -> app.approve(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }
}
