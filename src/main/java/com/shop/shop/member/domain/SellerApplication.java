package com.shop.shop.member.domain;

import com.shop.shop.common.crypto.EncryptedStringConverter;
import com.shop.shop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 판매자 신청 Entity.
 *
 * <p>테이블: seller_application (V5__seller_application.sql)
 * <p>상태머신: PENDING → APPROVED | REJECTED (터미널)
 * <p>시간 컬럼: BaseEntity 상속 — DB DEFAULT now() + 트리거 소유 (insertable/updatable=false).
 * <p>decidedAt: 심사 결정 시각 — 도메인이 명시적으로 set (BaseEntity와 별개).
 *
 * <p>Setter 사용 금지. 상태 전이는 의도 노출 메서드(approve/reject)로만 수행한다.
 * Entity를 API 응답/View 모델에 직접 전달하지 않는다 (DTO 변환 필수).
 */
@Entity
@Table(name = "seller_application")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SellerApplication extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 신청자 userId — FK users(id), 부분 유니크 인덱스 키 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 신청 상태 — VARCHAR(20) CHECK(PENDING/APPROVED/REJECTED) */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SellerApplicationStatus status;

    /** 상호명 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "business_name", nullable = false)
    private String businessName;

    /** 사업자등록번호 — 숫자 10자리 패턴 검증은 DTO 레이어에서 수행 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "business_registration_number", nullable = false)
    private String businessRegistrationNumber;

    /** 담당자 연락처 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_phone", nullable = false)
    private String contactPhone;

    /** 반려 사유 — nullable (REJECTED 시만 기록) */
    @Column(name = "reject_reason")
    private String rejectReason;

    /** 심사 ADMIN userId — nullable (심사 전 null) */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    /** 심사 결정 시각 — nullable (심사 전 null), 도메인이 명시 set */
    @Column(name = "decided_at")
    private Instant decidedAt;

    /**
     * 정적 팩토리 — 판매자 신청 제출 (status=PENDING 고정).
     *
     * @param userId                     신청자 userId
     * @param businessName               상호명
     * @param businessRegistrationNumber 사업자등록번호
     * @param contactPhone               담당자 연락처
     * @return status=PENDING인 신규 신청
     */
    public static SellerApplication submit(
            long userId,
            String businessName,
            String businessRegistrationNumber,
            String contactPhone) {

        SellerApplication app = new SellerApplication();
        app.userId = userId;
        app.status = SellerApplicationStatus.PENDING;
        app.businessName = businessName;
        app.businessRegistrationNumber = businessRegistrationNumber;
        app.contactPhone = contactPhone;
        return app;
    }

    /**
     * 판매자 신청 승인 — 상태를 APPROVED로 전이.
     *
     * <p>서비스가 PENDING 여부를 선제 확인(SellerApplicationStateConflictException)하므로,
     * 도메인 메서드는 방어적 가드만 수행한다.
     *
     * @param reviewerId 심사 ADMIN userId
     * @throws IllegalStateException 비-PENDING 상태에서 호출 시 (방어 가드 — 서비스 선제 확인 전제)
     */
    public void approve(long reviewerId) {
        if (this.status != SellerApplicationStatus.PENDING) {
            throw new IllegalStateException(
                    "PENDING 상태에서만 승인할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SellerApplicationStatus.APPROVED;
        this.reviewedBy = reviewerId;
        this.decidedAt = Instant.now();
    }

    /**
     * 판매자 신청 반려 — 상태를 REJECTED로 전이.
     *
     * <p>서비스가 PENDING 여부를 선제 확인(SellerApplicationStateConflictException)하므로,
     * 도메인 메서드는 방어적 가드만 수행한다.
     *
     * @param reviewerId 심사 ADMIN userId
     * @param reason     반려 사유
     * @throws IllegalStateException 비-PENDING 상태에서 호출 시 (방어 가드)
     */
    public void reject(long reviewerId, String reason) {
        if (this.status != SellerApplicationStatus.PENDING) {
            throw new IllegalStateException(
                    "PENDING 상태에서만 반려할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = SellerApplicationStatus.REJECTED;
        this.reviewedBy = reviewerId;
        this.rejectReason = reason;
        this.decidedAt = Instant.now();
    }
}
