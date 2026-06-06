package com.shop.shop.product.repository;

import com.shop.shop.product.domain.OptionValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * OptionValue JPA Repository.
 */
public interface OptionValueRepository extends JpaRepository<OptionValue, Long> {

    /**
     * 동일 옵션 내 옵션값 중복 여부 확인 (V2 불변식).
     */
    boolean existsByOptionIdAndValue(long optionId, String value);

    /**
     * 상품 소속 모든 옵션값 조회 (V6 검증용 — 상품 소속 optionValueId 집합 구성).
     */
    List<OptionValue> findByOption_ProductId(long productId);

    /**
     * 상품 소속 모든 옵션값 조회 — id 오름차순 정렬 보장 (공개 상세 노출용).
     */
    List<OptionValue> findByOption_ProductIdOrderByIdAsc(long productId);

    /**
     * 옵션별 옵션값 목록 조회 (id 순 정렬).
     */
    List<OptionValue> findByOptionIdOrderById(long optionId);
}
