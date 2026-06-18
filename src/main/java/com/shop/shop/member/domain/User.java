package com.shop.shop.member.domain;

import com.shop.shop.common.domain.BaseEntity;
import com.shop.shop.common.crypto.EncryptedStringConverter;
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
 * 회원 계정 Entity.
 *
 * <p>테이블: users (V1__init_schema.sql + V2__users_role_hierarchy.sql)
 * <p>이메일: citext — DB 레벨 대소문자 무시 (@Column columnDefinition="citext")
 * <p>role: VARCHAR CHECK(ADMIN/SELLER/CONSUMER) — @Enumerated(STRING), V2 마이그레이션으로 교체
 * <p>시간 컬럼: BaseEntity 상속 — DB DEFAULT now() + 트리거 소유, insertable=false/updatable=false
 *
 * <p>Setter 사용 금지. 상태 보유만 담당 (비밀번호 검증은 MemberService에서).
 * Entity를 API 응답으로 직접 반환하지 않음 (DTO 변환 필수).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 이메일 — citext 컬럼 (대소문자 무시).
     * columnDefinition="citext" 명시 → Hibernate validate 시 컬럼 타입 정합.
     */
    @Column(name = "email", columnDefinition = "citext", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "name", nullable = false)
    private String name;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "phone")
    private String phone;

    /**
     * 권한 — VARCHAR CHECK(ADMIN/SELLER/CONSUMER).
     * V2 마이그레이션으로 CHECK 제약이 CONSUMER/SELLER/ADMIN으로 교체됨.
     * @Enumerated(STRING)으로 enum 상수명과 1:1 매핑.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    /**
     * 계정 상태 — ACTIVE(활성) / WITHDRAWN(탈퇴).
     * V6 마이그레이션으로 추가. DEFAULT ACTIVE. NOT NULL.
     * @Enumerated(STRING)으로 enum 상수명과 1:1 매핑.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    /**
     * 탈퇴 시각 — 탈퇴(소프트 삭제) 시 기록. 활성 계정은 null.
     * V6 마이그레이션으로 추가. nullable.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * 마지막 로그인 시각 — 로그인 성공 시 갱신. 한 번도 로그인하지 않은 계정은 null.
     * V9 마이그레이션으로 추가. nullable.
     * timestamptz ↔ Instant 직접 매핑 (KST 표시는 프레젠테이션 레이어 책임).
     */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * 정적 팩토리 — 신규 회원 생성.
     * status=ACTIVE 기본 세팅. deletedAt=null (가입 시 활성 상태).
     */
    public static User of(String email, String passwordHash, String name, String phone, Role role) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.name = name;
        user.phone = phone;
        user.role = role;
        user.status = MemberStatus.ACTIVE;
        user.deletedAt = null;
        return user;
    }

    /**
     * 관리자에 의한 권한 변경 의도 노출 메서드.
     *
     * <p>Setter 사용 금지 규칙에 따라 의미 있는 이름의 메서드로 상태를 변경한다.
     * JPA dirty checking으로 트랜잭션 커밋 시 UPDATE가 실행된다.
     *
     * @param newRole 변경할 권한 (SELLER 또는 CONSUMER — MemberService 불변식이 보장)
     */
    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    /**
     * 비밀번호 변경.
     *
     * <p>BCrypt 해시를 직접 받는다 (서비스에서 encode 후 호출).
     * JPA dirty checking으로 트랜잭션 커밋 시 UPDATE가 실행된다.
     *
     * @param newPasswordHash BCrypt 인코딩된 새 비밀번호 해시
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    /**
     * 회원 정보 수정 (name/phone).
     *
     * <p>email/role/passwordHash는 이 메서드로 변경할 수 없다.
     * JPA dirty checking으로 트랜잭션 커밋 시 UPDATE가 실행된다.
     *
     * @param name  변경할 이름
     * @param phone 변경할 전화번호 (null 허용 — optional 필드)
     */
    public void updateProfile(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    /**
     * 탈퇴 처리 (소프트 삭제).
     *
     * <p>status=WITHDRAWN, deletedAt=현재 시각으로 전이한다.
     * 물리 삭제 없음 — 연관 데이터(주문 등) 보존.
     * 재탈퇴 호출은 멱등 (가드 차단 전제 — YAGNI).
     * JPA dirty checking으로 트랜잭션 커밋 시 UPDATE가 실행된다.
     */
    public void withdraw() {
        this.status = MemberStatus.WITHDRAWN;
        this.deletedAt = Instant.now();
    }

    /**
     * 로그인 성공 시각 기록.
     *
     * <p>REST·formLogin 두 경로에서 로그인 성공 후 호출된다.
     * Setter 금지 규칙에 따라 의미 있는 이름의 의도 메서드로 상태를 변경한다.
     * JPA dirty checking으로 트랜잭션 커밋 시 UPDATE가 실행된다.
     *
     * @param now 로그인 시각 (호출자가 Instant.now()로 전달 — Clock 빈 의존 금지)
     */
    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    /**
     * 계정 활성 여부 확인.
     *
     * @return 활성 계정이면 true, 탈퇴 계정이면 false
     */
    public boolean isActive() {
        return this.status == MemberStatus.ACTIVE;
    }
}
