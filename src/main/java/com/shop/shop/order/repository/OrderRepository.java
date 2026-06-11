package com.shop.shop.order.repository;

import com.shop.shop.order.domain.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * 결제 경로 전용: 소유권 검증 포함 주문 + items 즉시 로딩 (optionValues 제외).
     *
     * <p>@EntityGraph로 items·optionValues 동시 fetch 시 MultipleBagFetchException 발생.
     * 결제 이벤트 완결성 검증은 variantId만 필요하므로 items만 fetch한다.
     *
     * @param id     주문 ID
     * @param userId 소유자 userId
     * @return 주문 (items 즉시 로딩, optionValues 지연, 없으면 empty)
     */
    @Query("select distinct o from Order o left join fetch o.items where o.id = :id and o.userId = :userId")
    Optional<Order> findWithItemsOnlyByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    /**
     * 비관적 쓰기 락으로 주문 row 조회.
     *
     * <p>{@code PESSIMISTIC_WRITE} → PostgreSQL {@code SELECT ... FOR UPDATE}.
     * 주문 확정(pending→paid) 권위 직렬화에 사용한다.
     * 트랜잭션 안에서만 의미 있다 (OrderConfirmation @Transactional 경계 안에서 호출).
     *
     * <p>InventoryStockRepository.findByIdForUpdate 선례와 동형.
     *
     * @param id 주문 ID
     * @return 주문 (잠긴 row, 없으면 empty)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") long id);

    /**
     * 상태 목록으로 주문 페이지 조회 (이행 대상 목록 — admin facade).
     *
     * <p>admin facade의 {@code listFulfillableOrders}에서 {@code paid}/{@code preparing} 주문 페이지 조회에 사용한다.
     * 최신순(createdAt DESC, id DESC) 정렬.
     *
     * @param statuses 조회 대상 상태 목록 (예: ["paid", "preparing"])
     * @param pageable 페이지 요청
     * @return 해당 상태의 주문 페이지 (최신순)
     */
    Page<Order> findByStatusInOrderByCreatedAtDescIdDesc(
            Collection<String> statuses, Pageable pageable);
}
