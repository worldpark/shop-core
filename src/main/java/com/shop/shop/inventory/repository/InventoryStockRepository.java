package com.shop.shop.inventory.repository;

import com.shop.shop.inventory.domain.VariantStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * VariantStock JPA Repository — 비관적 락 재고 조회 전용.
 *
 * <p>findByIdForUpdate: PostgreSQL {@code SELECT ... FOR UPDATE} 발행.
 * 같은 variantId에 대한 동시 접근이 row 단위로 직렬화된다.
 */
public interface InventoryStockRepository extends JpaRepository<VariantStock, Long> {

    /**
     * 비관적 쓰기 락으로 재고 row 조회.
     *
     * <p>{@code PESSIMISTIC_WRITE} → PostgreSQL {@code SELECT ... FOR UPDATE}.
     * 트랜잭션 안에서만 의미 있다 (order @Transactional 경계 안에서 호출).
     *
     * @param id variant ID (= VariantStock.id)
     * @return VariantStock (잠긴 row, 없으면 empty)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select vs from VariantStock vs where vs.id = :id")
    Optional<VariantStock> findByIdForUpdate(@Param("id") long id);
}
