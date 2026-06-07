package com.shop.shop.cart.repository;

import com.shop.shop.cart.domain.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Cart JPA Repository.
 */
public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * userId로 장바구니 조회.
     *
     * @param userId 회원 userId
     * @return 장바구니 (없으면 empty)
     */
    Optional<Cart> findByUserId(long userId);
}
