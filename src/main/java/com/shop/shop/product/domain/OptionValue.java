package com.shop.shop.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 옵션값 Entity.
 *
 * <p>테이블: option_values (V1__init_schema.sql)
 * <p>option_id, value — UNIQUE(option_id, value) DB 제약.
 * <p>시간 컬럼 없음 — BaseEntity 비상속.
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} 사용.
 */
@Entity
@Table(name = "option_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OptionValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    private ProductOption option;

    @Column(nullable = false)
    private String value;

    /**
     * 옵션값 생성 정적 팩토리.
     *
     * @param option 소속 옵션 Entity (not null)
     * @param value  옵션값 문자열 (not null, 옵션 내 유일)
     * @return 새 OptionValue 인스턴스
     */
    public static OptionValue create(ProductOption option, String value) {
        OptionValue optionValue = new OptionValue();
        optionValue.option = option;
        optionValue.value = value;
        return optionValue;
    }
}
