package com.shop.shop.order.repository;

import com.shop.shop.order.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Order JPA Repository.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 내 주문 목록 최신순 조회.
     *
     * <p>created_at DESC, id DESC (타이브레이크) 정렬.
     * 메서드명 컨벤션으로 ORDER BY 자동 생성.
     *
     * @param userId   소유자 userId
     * @param pageable 페이지 요청
     * @return 최신순 주문 페이지
     */
    Page<Order> findByUserIdOrderByCreatedAtDescIdDesc(long userId, Pageable pageable);

    /**
     * 소유권 검증 포함 주문 조회.
     *
     * <p>미존재 또는 userId 불일치 모두 empty 반환 → OrderNotFoundException(404 존재 은닉).
     *
     * @param id     주문 ID
     * @param userId 소유자 userId
     * @return 주문 (없으면 empty)
     */
    Optional<Order> findByIdAndUserId(long id, long userId);

    /**
     * 소유권 검증 포함 주문 상세 조회 — items·optionValues N+1 회피.
     *
     * <p>@EntityGraph로 items·items.optionValues를 즉시 로딩한다.
     * 상세 조회 시 사용.
     *
     * @param id     주문 ID
     * @param userId 소유자 userId
     * @return 주문 (items·optionValues 즉시 로딩, 없으면 empty)
     */
    @EntityGraph(attributePaths = {"items", "items.optionValues"})
    Optional<Order> findWithItemsByIdAndUserId(long id, long userId);
}
