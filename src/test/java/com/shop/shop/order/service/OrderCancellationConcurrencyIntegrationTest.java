package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderCancellationConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.OrderCancellationResult;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.service.PaymentServiceResponse;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 주문 취소 동시성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>PESSIMISTIC_WRITE 락으로 동시 취소·취소-결제 경쟁 상황을 직렬화해야 한다.
 *
 * <p>검증:
 * <ul>
 *   <li>동시 취소 2건: 1건만 CANCELLED, 나머지 ALREADY_CANCELLED 또는 성공 — 중복 복원 없음</li>
 *   <li>취소 vs 결제 직렬화: 락 경합 — 결제 후 취소(환불) OR 취소 후 결제 시도(409) 중 하나.
 *       어느 쪽이 먼저 커밋하든 최종 상태는 단일 정합 조합이어야 함(2-D, 033).</li>
 *   <li>재고는 정합적으로 복원됨 (과복원 방지)</li>
 * </ul>
 *
 * <p>동시 취소 직렬화: {@code orderCancellation.cancel()} 안의 {@code findByIdForUpdate}(PESSIMISTIC_WRITE)가
 * 두 트랜잭션을 직렬화 — 선행 트랜잭션이 committed 후 후발이 ALREADY_CANCELLED로 멱등 반환.
 *
 * <p>취소 vs 결제 직렬화: 결제는 {@link PaymentServiceResponse#pay}(production 경로),
 * 취소는 {@link PaymentServiceResponse#cancel}(production 경로) — 양쪽 모두
 * {@code findByIdForUpdate}(PESSIMISTIC_WRITE)로 직렬화. 어느 쪽이 먼저 커밋하든
 * 최종 종착 상태가 단일 정합 조합(order/payment/stock)으로 수렴해야 한다.
 *
 * <p>1-A(033) 수정 검증: confirmPaid가 락 후 EntityManager.refresh(order)로 fresh 재판정해
 * 취소가 먼저 커밋해도 결제가 cancelled를 paid로 덮어쓰지 않는다.
 * 수정 전에는 cancelVsPay_* 정합성 단정이 RED(paid+stock=초기+1 과복원)여야 한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class OrderCancellationConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private PaymentServiceResponse paymentServiceResponse;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @BeforeEach
    void setUp() {
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));
    }

    // ============================================================
    // 동시 취소 2건 직렬화 (무변경 유지)
    // ============================================================

    @Test
    @DisplayName("동시 취소 2건: 1건만 CANCELLED(재고 복원 1회), 나머지 ALREADY_CANCELLED 또는 예외")
    void concurrentCancel_sameOrder_onlyOneCancelledAndStockRestoredOnce() throws Exception {
        // given
        long userId = insertUser("conc-cancel1@test.com");
        long variantId = insertVariantWithStock(5); // 초기 재고 5
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 2, BigDecimal.valueOf(3000)); // 2개 주문

        configProductMock(variantId, productId, BigDecimal.valueOf(3000));

        int cancelledCount = 0;
        int alreadyOrExceptionCount = 0;

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // 결과를 배열로 수집 (람다에서 접근 가능)
        int[] results = new int[2]; // 0=cancelled, 1=already/exception

        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    OrderCancellationResult result = orderCancellation.cancel(
                            orderId, userId, new RefundInfo(false, 0L, "KRW"));
                    if (result.outcome() == OrderCancellation.Outcome.CANCELLED) {
                        results[0]++;
                    } else {
                        results[1]++;
                    }
                } catch (OrderCancellationConflictException | OrderNotFoundException e) {
                    results[1]++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results[1]++;
                } catch (Exception e) {
                    // 락 대기 중 직렬화 오류 등 → already/exception으로 처리
                    results[1]++;
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then: 2건 처리 완료
        cancelledCount = results[0];
        alreadyOrExceptionCount = results[1];
        assertThat(cancelledCount + alreadyOrExceptionCount).isEqualTo(2);

        // 취소 성공은 정확히 1건
        assertThat(cancelledCount).isEqualTo(1);

        // orders.status = cancelled (1회만 전이)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("cancelled");

        // 재고 정확히 1회 복원: 5 + 2 = 7
        int stock = getStock(variantId);
        assertThat(stock).isEqualTo(7);
    }

    // ============================================================
    // 취소 vs 결제 직렬화 — 정합성 단정 (2-D, 033)
    // ============================================================

    /**
     * 취소(production 경로) vs 결제(production 경로) 동시 경합.
     *
     * <p>1-A(033) 수정 적용 후: 어느 스레드가 먼저 커밋하든 최종 종착 상태는 반드시
     * 다음 두 정합 조합 중 하나여야 한다:
     * <ul>
     *   <li>분기 A(취소 먼저 커밋): order=cancelled, stock=초기+1(복원 1회), payment 없음/cancelled</li>
     *   <li>분기 B(결제 먼저 커밋): order=paid 또는 refunded(환불 경로), stock=초기(미복원 or 환불 후 복원), payment paid/refunded</li>
     * </ul>
     * "paid인데 stock=초기+1(과복원)" 혼합 상태가 발생하면 실패한다(정합성 버그 재현).
     *
     * <p>cancel 스레드는 production 경로({@link PaymentServiceResponse#cancel})를 사용해
     * paid 경합 시 환불 경로로 정합 종결(분기 B)한다.
     *
     * <p>1-A 수정 전: 취소가 먼저 커밋해도 결제가 stale pending으로 cancelled를 paid로 덮어써
     * order=paid+stock=초기+1(과복원) 혼합 상태가 발생 → 이 단정이 RED여야 함.
     */
    @Test
    @DisplayName("취소 vs 결제 동시 경합: 최종 상태는 단일 정합 조합(과복원 없음)")
    void cancelVsPay_serialized_onlyOneSucceeds() throws Exception {
        // given
        long userId = insertUser("cancel-vs-pay1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 1, BigDecimal.valueOf(10000));

        configProductMock(variantId, productId, BigDecimal.valueOf(10000));

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 1: 취소 시도 (production 경로 — PaymentServiceResponse.cancel)
        // 결제가 먼저 커밋하면 paid 상태를 보고 환불 경로(PG refund + markRefunded)로 정합 종결
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                paymentServiceResponse.cancel(auth, orderId);
            } catch (Exception e) {
                // 취소 실패(409 등) — failCount 카운트 대신 최종 상태로 정합 검증
            } finally {
                done.countDown();
            }
        });

        // 스레드 2: 결제 시도 (production 경로 — PaymentServiceResponse.pay)
        // 취소가 먼저 커밋하면 confirmPaid가 fresh cancelled를 보고 REJECTED → 결제 실패(409)
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                paymentServiceResponse.pay(auth, orderId, null);
            } catch (Exception e) {
                // 결제 실패(409 등) — failCount 카운트 대신 최종 상태로 정합 검증
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // ── 정합성 단정 (타이밍 비의존) ──────────────────────────────────────
        // 카운트 단정 제거: 어느 스레드가 먼저 커밋하든 종착 상태가 단일 정합 조합인지를 단정한다.

        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        int stock = getStock(variantId);

        // 주문 상태는 반드시 단일 종결 상태여야 함 (pending은 둘 다 처리 안 된 이상 발생 불가)
        assertThat(orderStatus).isIn("cancelled", "paid", "refunded");

        if ("cancelled".equals(orderStatus)) {
            // 분기 A: 취소 먼저 커밋 (결제 스레드는 REJECTED → 롤백)
            // - 재고: 취소 1회 복원 = 10 + 1 = 11
            assertThat(stock).as("취소 종착: 재고는 1회 복원(11)이어야 합니다").isEqualTo(11);
            // - payment: 없거나 cancelled (결제 흐름이 롤백되었으므로 paid payment 없음)
            String paymentStatus = jdbc.queryForObject(
                    "SELECT COALESCE(status, 'none') FROM payments WHERE order_id=? " +
                    "UNION ALL SELECT 'none' WHERE NOT EXISTS (SELECT 1 FROM payments WHERE order_id=?) " +
                    "LIMIT 1",
                    String.class, orderId, orderId);
            assertThat(paymentStatus).as("취소 종착: payment는 없거나 cancelled여야 합니다")
                    .isIn("none", "cancelled", "ready", "failed"); // paid/refunded 금지
        } else if ("paid".equals(orderStatus)) {
            // 분기 B-1: 결제 먼저 커밋, 취소 환불 경로 미완료(또는 취소 자체 실패)
            // - 재고: 결제 후 취소 환불이 진행되지 않았으면 미복원 = 10
            // - paid인데 stock=11이면 과복원(정합성 버그) → 실패
            assertThat(stock).as(
                    "결제 종착(paid): 재고 과복원 금지 — paid인데 stock=" + stock + "은 정합성 버그(취소+결제 동시 성공)").isEqualTo(10);
            // - payment: paid
            String paymentStatus = jdbc.queryForObject(
                    "SELECT COALESCE(status, 'none') FROM payments WHERE order_id=? " +
                    "UNION ALL SELECT 'none' WHERE NOT EXISTS (SELECT 1 FROM payments WHERE order_id=?) " +
                    "LIMIT 1",
                    String.class, orderId, orderId);
            assertThat(paymentStatus).as("결제 종착(paid): payment는 paid여야 합니다").isEqualTo("paid");
        } else {
            // 분기 B-2: 결제 먼저 커밋 후 취소 환불 경로 완료 (order=refunded)
            // - 재고: 환불 후 재고 복원 = 10 + 1 = 11
            assertThat(stock).as("환불 종착(refunded): 재고는 환불 후 복원(11)이어야 합니다").isEqualTo(11);
            // - payment: refunded
            String paymentStatus = jdbc.queryForObject(
                    "SELECT COALESCE(status, 'none') FROM payments WHERE order_id=? " +
                    "UNION ALL SELECT 'none' WHERE NOT EXISTS (SELECT 1 FROM payments WHERE order_id=?) " +
                    "LIMIT 1",
                    String.class, orderId, orderId);
            assertThat(paymentStatus).as("환불 종착(refunded): payment는 refunded여야 합니다").isEqualTo("refunded");
        }
    }

    // ============================================================
    // confirmPaid 직렬화 (취소 먼저 커밋 → 결제 REJECTED) — 5.2절
    // ============================================================

    /**
     * 취소가 먼저 커밋(별도 트랜잭션)된 뒤 결제를 시도하면 REJECTED(409)를 받아야 한다.
     *
     * <p>1-A(033) 핵심 회귀: confirmPaid가 락 후 refresh로 fresh cancelled를 보고 REJECTED.
     * 수정 전(refresh 없음)에는 stale pending을 보고 markPaid → cancelled를 paid로 덮어쓴다.
     */
    @Test
    @DisplayName("취소 먼저 커밋 후 결제 시도 → OrderConfirmationConflictException(409)")
    void cancelFirst_thenPay_shouldBeRejected() {
        // given: pending 주문 셋업
        long userId = insertUser("cancel-first-pay@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 1, BigDecimal.valueOf(10000));

        configProductMock(variantId, productId, BigDecimal.valueOf(10000));

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        // 취소 먼저 커밋 (production 경로 — 별도 트랜잭션에서 완료)
        paymentServiceResponse.cancel(auth, orderId);

        // orders.status = cancelled 확인
        String statusAfterCancel = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(statusAfterCancel).isEqualTo("cancelled");

        // when: 이미 취소된 주문에 결제 시도
        // then: 취소를 paid로 덮어쓰지 않고 409 예외
        assertThatThrownBy(() -> paymentServiceResponse.pay(auth, orderId, null))
                .as("이미 취소된 주문에 결제 시도 시 OrderConfirmationConflictException(409)이어야 합니다")
                .isInstanceOf(com.shop.shop.common.exception.OrderConfirmationConflictException.class);

        // 주문 상태가 여전히 cancelled (paid로 덮어써지지 않음)
        String statusAfterPay = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(statusAfterPay).as("취소 후 결제 시도: 주문 상태가 cancelled 유지(paid로 덮어쓰기 금지)")
                .isEqualTo("cancelled");

        // 재고도 복원 상태 유지 (10 + 1 = 11)
        int stock = getStock(variantId);
        assertThat(stock).as("취소 후 결제 시도: 재고 복원 상태(11) 유지").isEqualTo(11);
    }

    /**
     * confirmPaid 멱등 분기 회귀: 이미 paid인 주문에 결제 확정 재시도 시 ALREADY_CONFIRMED.
     *
     * <p>refresh 후에도 paid를 감지해 멱등 경로로 정상 처리됨을 확인(1.3절 회귀).
     */
    @Test
    @DisplayName("결제 완료 주문 재결제 시도 → 멱등 처리(예외 없이 200)")
    void payAlreadyPaid_shouldBeIdempotent() {
        // given
        long userId = insertUser("idempotent-pay@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 1, BigDecimal.valueOf(10000));

        configProductMock(variantId, productId, BigDecimal.valueOf(10000));

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        // 첫 번째 결제
        PaymentResponse firstResult = paymentServiceResponse.pay(auth, orderId, null);
        assertThat(firstResult.status()).isEqualTo("paid");

        // when: 동일 주문 재결제 시도 (멱등)
        // then: 예외 없이 정상 응답 반환
        PaymentResponse secondResult = paymentServiceResponse.pay(auth, orderId, null);
        assertThat(secondResult.status()).as("이미 paid인 주문 재결제: 멱등 반환").isEqualTo("paid");

        // 주문 상태 여전히 paid
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("paid");
    }

    // ============================================================
    // doCancel 가드 단위/통합 테스트 (3-F, 033)
    // ============================================================

    /**
     * paid 주문에 RefundInfo(false, 0, "KRW")로 OrderCancellation.cancel 직접 호출
     * → 3-F 가드가 IllegalStateException 발생 + 상태·재고 무변경.
     *
     * <p>production 정상 흐름(PaymentService.cancel 경유)은 이 경로에 도달하지 않는다.
     * raw SPI 직접 호출이 모순 refundInfo를 전달할 경우를 방어.
     */
    @Test
    @DisplayName("paid 주문에 RefundInfo(false,0) 직접 취소 시도 → IllegalStateException(3-F 가드)")
    void doCancel_guard_paidWithUnrefundedInfo_throwsIllegalState() {
        // given: paid 상태 주문 셋업
        long userId = insertUser("guard-paid-cancel@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 1, BigDecimal.valueOf(10000));

        configProductMock(variantId, productId, BigDecimal.valueOf(10000));

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        // 먼저 결제 완료 (paid 상태로 전이)
        paymentServiceResponse.pay(auth, orderId, null);

        String statusBeforeCancel = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(statusBeforeCancel).isEqualTo("paid");

        // when: paid 주문에 환불 없이(refunded=false) OrderCancellation.cancel 직접 호출
        // then: 3-F 가드가 IllegalStateException을 던져야 함
        assertThatThrownBy(() ->
                orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW")))
                .as("paid 주문에 RefundInfo(false,0) 직접 호출: 3-F 가드 IllegalStateException")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");

        // 상태·재고 무변경 확인 (예외로 롤백됨)
        String statusAfter = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(statusAfter).as("가드 예외 후 주문 상태 무변경(paid 유지)").isEqualTo("paid");

        int stockAfter = getStock(variantId);
        assertThat(stockAfter).as("가드 예외 후 재고 무변경(10 유지)").isEqualTo(10);
    }

    /**
     * pending 주문 + RefundInfo(false, 0) 정상 취소 → 가드 통과, CANCELLED 반환.
     *
     * <p>정상 흐름 회귀: pending + refunded=false는 가드 조건을 충족하지 않으므로 통과.
     */
    @Test
    @DisplayName("pending 주문 + RefundInfo(false,0) 정상 취소 → 가드 통과(CANCELLED)")
    void doCancel_guard_pendingWithUnrefundedInfo_passes() {
        // given
        long userId = insertUser("guard-pending-cancel@test.com");
        long variantId = insertVariantWithStock(5);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 2, BigDecimal.valueOf(3000));

        configProductMock(variantId, productId, BigDecimal.valueOf(3000));

        // when: pending 주문에 RefundInfo(false, 0) — 정상 흐름
        OrderCancellationResult result = orderCancellation.cancel(
                orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then: 가드 통과, 정상 취소
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);

        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("cancelled");

        int stock = getStock(variantId);
        assertThat(stock).isEqualTo(7); // 5 + 2 = 7 (복원)
    }

    /**
     * pending 주문에 RefundInfo(true, amount) 직접 호출 → 3-F 가드 IllegalStateException.
     *
     * <p>pending(미결제) 주문에 환불 표기(refunded=true)는 모순 — 가드가 거부해야 함.
     */
    @Test
    @DisplayName("pending 주문에 RefundInfo(true, amount) 직접 호출 → 가드 IllegalStateException")
    void doCancel_guard_pendingWithRefundedInfo_throwsIllegalState() {
        // given
        long userId = insertUser("guard-pending-refund@test.com");
        long variantId = insertVariantWithStock(5);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 1, BigDecimal.valueOf(5000));

        configProductMock(variantId, productId, BigDecimal.valueOf(5000));

        // when: pending 주문인데 refunded=true (모순)
        // then: 3-F 가드 IllegalStateException
        assertThatThrownBy(() ->
                orderCancellation.cancel(orderId, userId, new RefundInfo(true, 5000L, "KRW")))
                .as("pending 주문에 RefundInfo(true, amount) 직접 호출: 3-F 가드 IllegalStateException")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refunded");

        // 상태·재고 무변경 (롤백 확인)
        String statusAfter = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(statusAfter).as("가드 예외 후 주문 상태 무변경(pending 유지)").isEqualTo("pending");

        int stockAfter = getStock(variantId);
        assertThat(stockAfter).as("가드 예외 후 재고 무변경(5 유지)").isEqualTo(5);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void configProductMock(long variantId, long productId, BigDecimal price) {
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, "동시성취소상품", null, List.of(),
                price, true, 100, "ON_SALE", true);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock) {
        String sku = "CANCEL-CONC-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('동시성취소상품', '설명', 5000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 5000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long getProductIdByVariant(long variantId) {
        return jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
    }

    private long insertPendingOrder(long userId, long variantId, int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-CONC-CANCEL-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, lineAmount, lineAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '동시성취소상품', ?, ?, ?)",
                orderId, variantId, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private int getStock(long variantId) {
        return jdbc.queryForObject(
                "SELECT pv.stock FROM product_variants pv WHERE pv.id=?", Integer.class, variantId);
    }
}
