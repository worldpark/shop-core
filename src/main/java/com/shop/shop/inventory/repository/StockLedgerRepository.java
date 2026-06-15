package com.shop.shop.inventory.repository;

import com.shop.shop.inventory.domain.StockLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재고 변동 원장 JPA Repository.
 *
 * <p>원장은 INSERT 전용(UPDATE 없음). 조회는 variant 기준 최신순 페이지네이션.
 */
public interface StockLedgerRepository extends JpaRepository<StockLedgerEntry, Long> {

    /**
     * variant별 원장 최신순 페이지 조회.
     *
     * <p>정렬 우선순위: occurred_at DESC (최신 순) → id DESC (동시각 안정 정렬).
     *
     * @param variantId variant ID
     * @param pageable  페이지 정보
     * @return 원장 항목 Page
     */
    Page<StockLedgerEntry> findByVariantIdOrderByOccurredAtDescIdDesc(long variantId, Pageable pageable);
}
