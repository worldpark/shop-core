package com.shop.shop.cart.domain;

import com.shop.shop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 Entity.
 *
 * <p>테이블: carts (V1__init_schema.sql)
 * <p>회원당 1개 — uq_carts_user_id UNIQUE 제약.
 * <p>userId 스칼라: member Entity 직접 참조 금지(architecture-rule 모듈 경계).
 * <p>created_at/updated_at: DB 소유 트리거 → BaseEntity 상속(읽기전용 매핑).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create(long)} 사용.
 */
@Entity
@Table(name = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소유자 userId 스칼라.
     * cart → member Entity 직접 참조를 피하기 위해 Long 스칼라로 보유한다.
     * FK 무결성은 DB(REFERENCES users(id))가 보장한다.
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * 장바구니 생성 정적 팩토리.
     *
     * @param userId 소유자 userId
     * @return 새 Cart 인스턴스
     */
    public static Cart create(long userId) {
        Cart cart = new Cart();
        cart.userId = userId;
        return cart;
    }
}
