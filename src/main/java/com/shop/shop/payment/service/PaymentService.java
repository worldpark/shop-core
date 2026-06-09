package com.shop.shop.payment.service;

import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.PaymentAmountMismatchException;
import com.shop.shop.common.exception.PaymentInProgressException;
import com.shop.shop.common.exception.OrderConfirmationConflictException;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.order.spi.OrderConfirmation.OrderConfirmationResult;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.order.spi.OrderPaymentReader.OrderPaymentView;
import com.shop.shop.order.spi.OrderPaymentReader.OrderSnapshotView;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationRequest;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 도메인 서비스.
 *
 * <p>모든 메서드 첫 인자 long userId — principal 이중경로(REST=userId, View=email→userId) 통일.
 * order.domain/repository 직접 참조 금지 — order.spi(OrderPaymentReader/OrderConfirmation)만 사용(P1).
 *
 * <p>처리 8단계 고정(Revision 1 #2):
 * <ol>
 *   <li>준비 스냅샷 조회(getPayableOrder — 소유권 404 + 이벤트 완결성 409)</li>
 *   <li>멱등/충돌 1차 판정 (paid → 멱등 200 / 비정상 → 409)</li>
 *   <li>금액 검증 (불일치 → 400)</li>
 *   <li>payments ready row 선점 INSERT (uq_payments_order_id → 직렬화)</li>
 *   <li>PG 승인 (선점 1건만 도달 — 모의 항상 승인)</li>
 *   <li>ready → paid 전이</li>
 *   <li>OrderConfirmation.confirmPaid (orders 비관락 + 권위 재검증 + 이벤트 발행)</li>
 *   <li>커밋</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderPaymentReader orderPaymentReader;
    private final OrderConfirmation orderConfirmation;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentRepository paymentRepository;

    /**
     * 결제 처리 — 8단계 고정 흐름.
     *
     * <p>단일 {@code @Transactional}: payments 기록 + orders 확정 + 이벤트 발행이 하나의 커밋 단위.
     * 실패 시 ready row 포함 전체 롤백(부분 반영 없음).
     *
     * @param userId  소유자 userId
     * @param orderId 주문 ID
     * @param cmd     결제 커맨드
     * @return 결제 결과
     */
    @Transactional
    public PaymentResult pay(long userId, long orderId, PaymentCommand cmd) {

        // ① 준비 스냅샷 조회 (소유권 404 + 이벤트 완결성 사전검증 409)
        OrderPaymentView snapshot = orderPaymentReader.getPayableOrder(orderId, userId);

        // ② 멱등/충돌 1차 판정 (빠른 경로 — 락 없음, race 가능하므로 7단계에서 권위 재검증)
        if ("paid".equals(snapshot.status())) {
            Payment existingPayment = paymentRepository.findByOrderId(orderId).orElse(null);
            if (existingPayment != null && "paid".equals(existingPayment.getStatus())) {
                log.info("이미 완료된 결제 — 멱등 반환: orderId={}", orderId);
                return new PaymentResult(existingPayment, snapshot.orderNumber());
            }
        }

        if (!"pending".equals(snapshot.status())) {
            log.warn("비정상 주문 상태 결제 시도: orderId={}, status={}", orderId, snapshot.status());
            throw new OrderConfirmationConflictException(
                    "주문 상태(" + snapshot.status() + ")에서 결제를 진행할 수 없습니다.");
        }

        // ③ 금액 검증 (클라이언트 금액 전달 시)
        BigDecimal finalAmount = snapshot.finalAmount();
        if (cmd.amount() != null && cmd.amount().compareTo(finalAmount) != 0) {
            log.warn("결제 금액 불일치: orderId={}, cmd.amount={}, finalAmount={}",
                    orderId, cmd.amount(), finalAmount);
            throw new PaymentAmountMismatchException();
        }

        String method = cmd.methodOrDefault();

        // ④ payments ready row 선점 INSERT (PG 호출 전 직렬화 핵심, #2)
        // uq_payments_order_id: 동시 2건 중 1건만 INSERT 성공 → PG 단일 호출 보장
        Payment payment = acquireOrResolveReadyRow(orderId, method, finalAmount);

        // ⑤ PG 승인 (락 밖, ready 선점으로 이미 단일화됨)
        // 실 PG 전환 시 idempotencyKey로 at-most-once charge 보장
        // 주석: 이 단계가 PG 호출 유일 지점 — orders row 락 밖에서 수행
        String idempotencyKey = payment.getId() != null
                ? String.valueOf(payment.getId())
                : snapshot.orderNumber();
        PaymentAuthorizationResult authResult = paymentGatewayPort.authorize(
                new PaymentAuthorizationRequest(
                        snapshot.orderNumber(),
                        finalAmount,
                        snapshot.currency(),
                        method,
                        idempotencyKey
                )
        );

        // 016: 모의 항상 승인. 거절 분기는 017에서 추가.
        if (!authResult.approved()) {
            // 017에서 처리 — 현재는 도달하지 않음
            throw new IllegalStateException("결제가 거절되었습니다. failureCode=" + authResult.failureCode());
        }

        // ⑥ ready → paid 전이
        payment.markPaid(authResult.pgTransactionId(), Instant.now());

        // ⑦ 주문 확정 위임 (orders row 비관락 + 권위 재검증 + OrderCompletedEvent 발행)
        // 주석: orders row 락은 여기 이후(confirmPaid 내부)에서만 획득 — P1
        OrderConfirmationResult confirmResult = orderConfirmation.confirmPaid(orderId, userId, finalAmount);

        log.info("결제 완료: orderId={}, paymentId={}, orderNumber={}, pgTxId={}",
                orderId, payment.getId(), snapshot.orderNumber(), authResult.pgTransactionId());

        // ⑧ 커밋 (payments paid + orders paid + event_publication 1행 원자 커밋)
        return new PaymentResult(payment, snapshot.orderNumber());
    }

    /**
     * 결제 상태 조회 — 소유권 검증 + payments row 조립.
     *
     * <p>{@link OrderPaymentReader#getOrderSnapshot} 사용(상태 조회 전용 — 완결성 409 없음, #3).
     * productId/연락처 해석 실패가 상태 조회를 깨뜨리지 않는다.
     *
     * @param userId  소유자 userId
     * @param orderId 주문 ID
     * @return 결제 상태 결과
     */
    @Transactional(readOnly = true)
    public PaymentStatusResult getPaymentStatus(long userId, long orderId) {
        // 소유권 검증 (404) — 완결성 검증 없음(#3)
        OrderSnapshotView snapshot = orderPaymentReader.getOrderSnapshot(orderId, userId);

        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);

        String paymentStatus = payment != null ? payment.getStatus() : "none";
        boolean paid = "paid".equals(paymentStatus);
        boolean payable = "pending".equals(snapshot.status()) && !paid;
        BigDecimal amount = payment != null ? payment.getAmount() : snapshot.finalAmount();
        Instant paidAt = payment != null ? payment.getPaidAt() : null;

        return new PaymentStatusResult(
                orderId,
                snapshot.orderNumber(),
                paymentStatus,
                paid,
                payable,
                amount,
                paidAt,
                payment != null ? payment.getId() : null,
                payment != null ? payment.getMethod() : null,
                payment != null ? payment.getPgTransactionId() : null
        );
    }

    /**
     * ready row 선점 또는 기존 row 재사용.
     *
     * <p>처리:
     * <ul>
     *   <li>기존 row 없음 → {@code Payment.create(...)} INSERT({@code saveAndFlush}).</li>
     *   <li>uq 위반({@code DataIntegrityViolationException}) → 재조회:
     *     <ul>
     *       <li>재조회 paid → 멱등 반환(PaymentResult로 상위에서 처리하기 위해 paid payment 반환)</li>
     *       <li>재조회 ready → {@link PaymentInProgressException}(409)</li>
     *     </ul>
     *   </li>
     *   <li>기존 row paid → 멱등 반환</li>
     *   <li>기존 row ready → 동일 row 재사용</li>
     * </ul>
     *
     * @param orderId     주문 ID
     * @param method      결제 수단
     * @param finalAmount 주문 금액
     * @return 선점/재사용된 payment row
     */
    private Payment acquireOrResolveReadyRow(long orderId, String method, BigDecimal finalAmount) {
        // 기존 row 조회
        Payment existing = paymentRepository.findByOrderId(orderId).orElse(null);

        if (existing != null) {
            if ("paid".equals(existing.getStatus())) {
                // 이미 paid — 상위로 반환(멱등 처리는 ⑦ confirmPaid에서 처리)
                return existing;
            }
            if ("ready".equals(existing.getStatus())) {
                // 같은 흐름의 재시도 — 동일 row 재사용
                return existing;
            }
        }

        // 신규 INSERT (ready 선점)
        Payment newPayment = Payment.create(orderId, method, finalAmount);
        try {
            // saveAndFlush: unique 충돌이 트랜잭션 경계에서 즉시 드러남 (#2)
            return paymentRepository.saveAndFlush(newPayment);
        } catch (DataIntegrityViolationException e) {
            // uq_payments_order_id 위반 — 동시 요청 경합
            log.warn("payments INSERT 경합(uq_payments_order_id): orderId={}", orderId);
            Payment concurrent = paymentRepository.findByOrderId(orderId).orElse(null);
            if (concurrent != null && "paid".equals(concurrent.getStatus())) {
                // 선점 측이 이미 paid 완료 — 멱등 반환
                return concurrent;
            }
            // ready 잔존 (드문 비정상) → in-progress 409
            throw new PaymentInProgressException();
        }
    }

    // ============================================================
    // 내부 결과 타입 (Entity 미노출)
    // ============================================================

    /**
     * 결제 처리 결과 (내부 타입).
     */
    public record PaymentResult(Payment payment, String orderNumber) {}

    /**
     * 결제 상태 조회 결과 (내부 타입).
     */
    public record PaymentStatusResult(
            long orderId,
            String orderNumber,
            String paymentStatus,
            boolean paid,
            boolean payable,
            BigDecimal amount,
            Instant paidAt,
            Long paymentId,
            String method,
            String pgTransactionId
    ) {}

    /**
     * 결제 커맨드 (내부 타입, ServiceResponse/FacadeImpl이 변환).
     */
    public record PaymentCommand(String method, BigDecimal amount) {

        /** method null/빈 문자열이면 기본값 "mock" 반환. */
        public String methodOrDefault() {
            return (method != null && !method.isBlank()) ? method : "mock";
        }
    }
}
