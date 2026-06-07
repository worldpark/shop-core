package com.shop.shop.cart.repository;

import com.shop.shop.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * CartItem JPA Repository.
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /**
     * 장바구니 항목 목록 조회.
     *
     * @param cartId 장바구니 ID
     * @return 장바구니 항목 목록
     */
    List<CartItem> findByCartId(long cartId);

    /**
     * 특정 variant의 장바구니 항목 조회 (재담기/생성 경합 재조회용).
     *
     * @param cartId    장바구니 ID
     * @param variantId variant ID
     * @return 장바구니 항목 (없으면 empty)
     */
    Optional<CartItem> findByCartIdAndVariantId(long cartId, long variantId);

    /**
     * stock 검증 포함 atomic 증가 UPDATE.
     *
     * <p>동시 재담기 시 합산 결과가 stock을 초과하면 WHERE 조건 불충족으로 UPDATE 실패(affected row 0).
     * read-modify-write 금지 — 이 메서드 단일 수단으로 재담기 증가를 처리한다.
     * addedAt은 UPDATE 대상에서 제외하여 최초 담은 시점을 보존한다.
     *
     * <p>SQL:
     * {@code UPDATE cart_items SET quantity = quantity + :delta
     *         WHERE cart.id = :cartId AND variantId = :variantId AND (quantity + :delta) <= :stock}
     *
     * @param cartId    장바구니 ID
     * @param variantId variant ID
     * @param delta     증가 수량 (≥ 1)
     * @param stock     현재 variant stock(ProductPurchaseCatalog 조회값) — WHERE 합산 검증 기준
     * @return 영향받은 row 수 (0이면 합산 stock 초과 거부)
     */
    @Modifying
    @Query("UPDATE CartItem ci SET ci.quantity = ci.quantity + :delta " +
           "WHERE ci.cart.id = :cartId AND ci.variantId = :variantId " +
           "AND (ci.quantity + :delta) <= :stock")
    int increaseQuantityWithinStock(
            @Param("cartId") long cartId,
            @Param("variantId") long variantId,
            @Param("delta") int delta,
            @Param("stock") int stock);
}
