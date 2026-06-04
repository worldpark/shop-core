package com.shop.shop.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BaseEntity 읽기 전용 매핑 검증 테스트.
 *
 * <p>revision 001 반영 — BaseEntity는 DB 소유(DEFAULT now() + 트리거)로 교정되었다.
 * JPA auditing(@CreatedDate/@LastModifiedDate/@EntityListeners) 제거.
 * 두 컬럼은 insertable=false, updatable=false (읽기 전용).
 *
 * <p>검증 목표:
 * - @MappedSuperclass 유지
 * - @EntityListeners(AuditingEntityListener) 부재
 * - @CreatedDate / @LastModifiedDate 부재
 * - createdAt: @Column(insertable=false, updatable=false)
 * - updatedAt: @Column(insertable=false, updatable=false)
 */
class BaseEntityTest {

    @Test
    @DisplayName("BaseEntity는 @MappedSuperclass 애너테이션을 가진다")
    void baseEntity_has_mappedSuperclass_annotation() {
        assertThat(BaseEntity.class.isAnnotationPresent(MappedSuperclass.class)).isTrue();
    }

    @Test
    @DisplayName("BaseEntity는 @EntityListeners(AuditingEntityListener.class)를 가지지 않는다 (DB 소유 전환)")
    void baseEntity_does_not_have_auditingEntityListener() {
        jakarta.persistence.EntityListeners annotation =
                BaseEntity.class.getAnnotation(jakarta.persistence.EntityListeners.class);
        if (annotation != null) {
            for (Class<?> listener : annotation.value()) {
                assertThat(listener).isNotEqualTo(AuditingEntityListener.class);
            }
        }
        // annotation 자체가 없는 경우도 통과 (제거된 경우)
    }

    @Test
    @DisplayName("createdAt 필드는 @CreatedDate를 가지지 않는다 (DB 소유 전환)")
    void createdAt_does_not_have_createdDate_annotation() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        assertThat(createdAtField.isAnnotationPresent(CreatedDate.class)).isFalse();
    }

    @Test
    @DisplayName("updatedAt 필드는 @LastModifiedDate를 가지지 않는다 (DB 소유 전환)")
    void updatedAt_does_not_have_lastModifiedDate_annotation() throws NoSuchFieldException {
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        assertThat(updatedAtField.isAnnotationPresent(LastModifiedDate.class)).isFalse();
    }

    @Test
    @DisplayName("createdAt 필드는 @Column(insertable=false, updatable=false)를 가진다 (읽기 전용)")
    void createdAt_field_is_readonly_column() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        Column column = createdAtField.getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.insertable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }

    @Test
    @DisplayName("updatedAt 필드는 @Column(insertable=false, updatable=false)를 가진다 (읽기 전용)")
    void updatedAt_field_is_readonly_column() throws NoSuchFieldException {
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        Column column = updatedAtField.getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.insertable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }

    @Test
    @DisplayName("createdAt / updatedAt 필드 타입은 Instant 이다")
    void timeFields_are_instant_type() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");

        assertThat(createdAtField.getType()).isEqualTo(Instant.class);
        assertThat(updatedAtField.getType()).isEqualTo(Instant.class);
    }
}
