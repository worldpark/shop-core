package com.shop.shop.member.domain;

import com.shop.shop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Column(name = "name", nullable = false)
    private String name;

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

    /** 정적 팩토리 — 테스트/복원용 (실 사용은 회원가입 후속 Task) */
    public static User of(String email, String passwordHash, String name, String phone, Role role) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.name = name;
        user.phone = phone;
        user.role = role;
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
}
