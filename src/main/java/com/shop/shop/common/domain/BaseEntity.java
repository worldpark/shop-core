package com.shop.shop.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.time.Instant;

/**
 * 모든 도메인 Entity의 공통 시간 컬럼 슈퍼클래스.
 *
 * <p>created_at / updated_at은 DB가 소유한다 (DEFAULT now() + set_updated_at 트리거).
 * JPA는 INSERT/UPDATE 시 두 컬럼을 제외하고(insertable=false, updatable=false),
 * persist 후 DB가 채운 값을 읽기 전용으로 반영한다.
 *
 * <p>@CreatedDate / @LastModifiedDate / @EntityListeners(AuditingEntityListener) 제거.
 * JpaAuditingConfig(@EnableJpaAuditing)도 shop-core에서 불필요하므로 삭제.
 *
 * <p>revision 001 — BaseEntity 시간 컬럼 소유권 변경 (DB 소유) 반영.
 */
@MappedSuperclass
@Getter
public class BaseEntity {

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
