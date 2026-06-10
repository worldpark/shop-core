package com.shop.shop.payment.service;

import com.shop.shop.common.exception.AmountConversionException;
import com.shop.shop.common.exception.OrderCancellationConflictException;
import com.shop.shop.common.exception.OrderConfirmationConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.PaymentAmountMismatchException;
import com.shop.shop.common.exception.PaymentEventResolutionException;
import com.shop.shop.common.exception.PaymentInProgressException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.OrderCancellationResult;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.order.spi.OrderConfirmation.OrderConfirmationResult;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.order.spi.OrderPaymentReader.OrderPaymentView;
import com.shop.shop.order.spi.OrderPaymentReader.OrderSnapshotView;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.event.PaymentFailedEvent;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationRequest;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentRefundRequest;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentRefundResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
 * member.spi(MemberDirectory.findContactByUserId)로 연락처 직접 조회(Mi1).
 *
 * <p>처리 8단계 고정(017 확장):
 * <ol>
 *   <li>준비 스냅샷 조회(getPayableOrder — 소유권 404 + 이벤트 완결성 409)</li>
 *   <li>멱등/충돌 1차 판정 (paid → 멱등 200 / 비정상 → 409)</li>
 *   <li>금액 검증 (불일치 → 400)</li>
 *   <li>연락처 사전 해석 (PG 호출 전 1회 — Mi1·모순4 후자)</li>
 *   <li>payments ready row 선점 INSERT (uq_payments_order_id → 직렬화)</li>
 *   <li>PG 승인 (선점 1건만 도달 — "동시 PG 호출은 1건" 불변식)</li>
 *   <li>⑥-A 승인: ready/failed→paid 전이 + 주문 확정</li>
 *   <li>⑥-B 거절: markFailed + PaymentFailedEvent 발행 + 주문 pending 유지(C1)</li>
 *   <li>커밋</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderPaymentReader orderPaymentReader;
    private final OrderConfirmation orderConfirmation;
    private final OrderCancellation orderCancellation;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentRepository paymentRepository;
    private final MemberDirectory memberDirectory;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 결제 처리 — 8단계 고정 흐름(017 확장: 거절 분기 + PaymentFailedEvent 발행).
     *
     * <p>단일 {@code @Transactional}: payments 기록 + orders 확정 + 이벤트 발행이 하나의 커밋 단위.
     * 실패 시 전체 롤백(부분 반영 없음).
     *
     * <p><b>거절 처리(C1)</b>: PG 거절 시 예외를 던지지 않고 거절 결과를 정상 반환한다.
     * {@code payments.status=failed} + {@code PaymentFailedEvent} Outbox가 정상 커밋된다.
     * 402 응답 변환은 커밋 이후 ServiceResponse/FacadeImpl 계층(트랜잭션 밖)에서 수행한다.
     *
     * @param userId  소유자 userId
     * @param orderId 주문 ID
     * @param cmd     결제 커맨드
     * @return 결제 결과 (승인: declined=false / 거절: declined=true)
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
                return PaymentResult.approved(existingPayment, snapshot.orderNumber());
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

        // ④ 연락처 사전 해석 (PG 호출 전 1회 — Mi1·모순4 후자)
        // 해석 실패는 PG 호출 전에 드러나 PaymentEventResolutionException(409)으로 처리된다.
        // ⑥-B 거절 분기는 이 값을 재사용하고 member.spi를 재조회하지 않는다 →
        // 거절 커밋 구간(PG 호출 후)에 외부 조회가 없어 거절은 항상 정상 커밋된다(C1 보장).
        MemberContact memberContact = resolveContactOrThrow(snapshot.userId());

        // ⑤ payments ready row 선점 INSERT (PG 호출 전 직렬화 핵심, #2)
        // uq_payments_order_id: 동시 2건 중 1건만 INSERT 성공 → "동시 PG 호출은 1건" 불변식 유지
        Payment payment = acquireOrResolveReadyRow(orderId, method, finalAmount);

        // ⑥ PG 승인 (락 밖, ready 선점으로 이미 단일화됨)
        // 실 PG 전환 시 idempotencyKey로 at-most-once charge 보장
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

        if (!authResult.approved()) {
            // ⑥-B 거절 분기 (C1 — 예외 없이 정상 반환)
            return handleDeclined(payment, snapshot, memberContact, finalAmount, authResult);
        }

        // ⑥-A 승인 경로 (016 그대로)
        payment.markPaid(authResult.pgTransactionId(), Instant.now());

        // ⑦ 주문 확정 위임 (orders row 비관락 + 권위 재검증 + OrderCompletedEvent 발행)
        // REJECTED→되던짐으로 현행 강일관성/롤백 보존.
        // 미래 HTTP 분리 시 여기에 UNKNOWN(timeout) 분기를 추가(재시도/정산)만 하면 됨.
        OrderConfirmationResult confirmResult = orderConfirmation.confirmPaid(orderId, userId, finalAmount);
        if (confirmResult.outcome() == OrderConfirmation.Outcome.REJECTED) {
            throw new OrderConfirmationConflictException(confirmResult.rejectedReason());
        }

        log.info("결제 완료: orderId={}, paymentId={}, orderNumber={}, pgTxId={}",
                orderId, payment.getId(), snapshot.orderNumber(), authResult.pgTransactionId());

        // ⑧ 커밋 (payments paid + orders paid + event_publication 1행 원자 커밋)
        return PaymentResult.approved(payment, snapshot.orderNumber());
    }

    /**
     * 주문 취소 오케스트레이션 — 취소 전용 locked reader + 상태 판정 + (paid)환불 + OrderCancellation 위임.
     *
     * <p>단일 {@code @Transactional}: 환불·재고 복원·종결 전이·이벤트 발행이 한 커밋 단위.
     * 실패 시 전체 롤백(부분 반영 없음).
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>취소 전용 locked reader({@link OrderPaymentReader#getOrderForCancel})로 orders row PESSIMISTIC_WRITE 잠금
     *       + 소유권 404 + 스냅샷 획득(#4). 환불 결정보다 먼저 호출(#4).</li>
     *   <li>상태 판정(권위·PG refund 전, #3):
     *     <ul>
     *       <li>이행단계(preparing/shipping/delivered) → {@link OrderCancellationConflictException}(409, 부작용 전 throw)</li>
     *       <li>이미 cancelled/refunded → 멱등 반환({@link CancelResult#already})</li>
     *       <li>paid → 환불 경로</li>
     *       <li>pending → 미결제 취소 경로</li>
     *     </ul>
     *   </li>
     *   <li>(paid) PG refund + Payment.markRefunded. (pending) Payment.markCancelled 또는 no-op.</li>
     *   <li>{@link OrderCancellation#cancel} 위임 → 종결 전이·재고 복원·OrderCancelledEvent.
     *       REJECTED/ALREADY 반환 시 {@link IllegalStateException}(500, 락 불변식 위반, 정상 흐름 미발생).</li>
     *   <li>성공 시 CancelResult 반환 (원자 커밋 — #2).</li>
     * </ol>
     *
     * @param userId  소유자 userId
     * @param orderId 주문 ID
     * @return 취소 결과
     * @throws OrderCancellationConflictException 이행단계 취소 불가 (409, 부작용 발생 전 throw)
     * @throws OrderNotFoundException             타인/미존재 주문 (404)
     */
    @Transactional
    public CancelResult cancel(long userId, long orderId) {
        // ① 취소 전용 locked reader — orders row PESSIMISTIC_WRITE 잠금 + 소유권 404 (#4)
        // 환불 결정 전에 호출해 confirmPaid와 직렬화
        OrderSnapshotView snapshot = orderPaymentReader.getOrderForCancel(orderId, userId);

        String status = snapshot.status();

        // ② 상태 판정 (권위·PG refund 전, #3 — 부작용 발생 전 판정)
        if ("preparing".equals(status) || "shipping".equals(status) || "delivered".equals(status)) {
            log.warn("이행단계 취소 시도(PG refund 전 차단): orderId={}, status={}", orderId, status);
            throw new OrderCancellationConflictException(
                    "이행단계(" + status + ") 주문은 취소할 수 없습니다. 배송 완료 후 반품을 이용하세요.");
        }

        if ("cancelled".equals(status) || "refunded".equals(status)) {
            log.info("이미 취소/환불된 주문 — 멱등 반환: orderId={}, status={}", orderId, status);
            boolean wasRefunded = "refunded".equals(status);
            long alreadyRefundedAmount = wasRefunded ? toLongExact(snapshot.finalAmount()) : 0L;
            return CancelResult.already(orderId, snapshot.orderNumber(), status, alreadyRefundedAmount, snapshot.currency());
        }

        // ③ 결제 row 처리
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        boolean refunded = false;
        long refundedAmount = 0L;

        if ("paid".equals(status)) {
            // 결제완료 경로 — PG 환불 후 markRefunded
            if (payment == null) {
                // paid 상태인데 payment row 없으면 시스템 불변식 위반
                throw new IllegalStateException(
                        "주문 상태가 paid인데 payment row가 없습니다. orderId=" + orderId);
            }

            String idempotencyKey = String.valueOf(payment.getId());
            PaymentRefundResult refundResult = paymentGatewayPort.refund(
                    new PaymentRefundRequest(
                            payment.getPgTransactionId(),
                            payment.getAmount(),
                            snapshot.currency(),
                            idempotencyKey
                    )
            );

            if (!refundResult.refunded()) {
                // 본 Task mock은 항상 성공 — 실 PG에서만 발생 가능
                log.error("PG 환불 실패: orderId={}, failureCode={}", orderId, refundResult.failureCode());
                throw new IllegalStateException("PG 환불에 실패했습니다: " + refundResult.failureReason());
            }

            payment.markRefunded(refundResult.pgRefundId());
            refunded = true;
            refundedAmount = toLongExact(payment.getAmount());

            log.info("PG 환불 성공: orderId={}, paymentId={}, pgRefundId={}",
                    orderId, payment.getId(), refundResult.pgRefundId());

        } else if ("pending".equals(status)) {
            // 미결제 경로 — 결제 row 있으면 markCancelled, 없으면 no-op
            if (payment != null) {
                payment.markCancelled();
            }
        }

        // ④ OrderCancellation 위임 — 종결 전이 + 재고 복원 + OrderCancelledEvent 발행
        RefundInfo refundInfo = new RefundInfo(refunded, refundedAmount, snapshot.currency());
        OrderCancellationResult cancellationResult = orderCancellation.cancel(orderId, userId, refundInfo);

        // 방어 검증(#3): 2단계에서 이행단계·종결을 선판정했으므로 정상 흐름엔 REJECTED/ALREADY_CANCELLED 미발생
        // 발생 시 락 불변식 위반 → 500 (전체 롤백)
        if (cancellationResult.outcome() == OrderCancellation.Outcome.REJECTED) {
            throw new IllegalStateException(
                    "OrderCancellation이 REJECTED를 반환했습니다 — 락 불변식 위반(#3). orderId=" + orderId
                    + ", reason=" + cancellationResult.rejectedReason());
        }
        if (cancellationResult.outcome() == OrderCancellation.Outcome.ALREADY_CANCELLED) {
            throw new IllegalStateException(
                    "OrderCancellation이 ALREADY_CANCELLED를 반환했습니다 — 락 불변식 위반(#3). orderId=" + orderId);
        }

        log.info("주문 취소 완료: orderId={}, orderNumber={}, orderStatus={}, refunded={}",
                orderId, snapshot.orderNumber(), cancellationResult.orderStatus(), refunded);

        // ⑤ 성공 — 원자 커밋 (#2)
        if (refunded) {
            return CancelResult.refunded(orderId, snapshot.orderNumber(), cancellationResult.orderStatus(),
                    refundedAmount, snapshot.currency());
        } else {
            return CancelResult.cancelled(orderId, snapshot.orderNumber(), cancellationResult.orderStatus());
        }
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

    // ============================================================
    // private 헬퍼
    // ============================================================

    /**
     * 거절 분기 처리 (⑥-B).
     *
     * <p>C1: 예외를 던지지 않고 거절 결과를 정상 반환. 트랜잭션이 정상 커밋되어
     * {@code payments.status=failed} UPDATE + {@code event_publication} INSERT가 원자 커밋된다.
     * 402 응답 변환은 커밋 이후 ServiceResponse/FacadeImpl 계층에서 수행한다.
     *
     * @param payment       선점한 payments row
     * @param snapshot      주문 스냅샷
     * @param memberContact 사전 해석된 연락처 (재조회 없음)
     * @param finalAmount   결제 금액 (BigDecimal)
     * @param authResult    PG 거절 결과
     * @return 거절 PaymentResult (declined=true)
     */
    private PaymentResult handleDeclined(
            Payment payment,
            OrderPaymentView snapshot,
            MemberContact memberContact,
            BigDecimal finalAmount,
            PaymentAuthorizationResult authResult
    ) {
        Instant attemptedAt = Instant.now();

        // 1. ready → failed 전이 (failed → failed는 no-op 멱등)
        payment.markFailed(authResult.failureCode(), authResult.failureReason());

        // 2. amount long 변환 (P3: longValueExact, 위반 시 AmountConversionException 500)
        long amountLong = toLongExact(finalAmount);

        // 3. PaymentFailedEvent 구성 (사전 해석한 연락처 재사용 — 재조회 없음)
        PaymentFailedEvent event = PaymentFailedEvent.of(
                snapshot.orderId(),
                snapshot.orderNumber(),
                snapshot.userId(),
                memberContact.email(),
                memberContact.name(),
                amountLong,
                snapshot.currency(),       // snapshot.currency() — 하드코딩 "KRW" 금지(모순5)
                authResult.failureCode(),
                authResult.failureReason(),
                attemptedAt
        );

        // 4. 발행 (같은 트랜잭션 = Outbox 저장, 커밋 후 Kafka 외부화)
        eventPublisher.publishEvent(event);

        // 5. 주문 pending 유지 (confirmPaid 미호출, OrderCompletedEvent 미발행)
        log.info("결제 거절: orderId={}, paymentId={}, orderNumber={}, failureCode={}",
                snapshot.orderId(), payment.getId(), snapshot.orderNumber(), authResult.failureCode());

        // 6. 거절 결과 반환 (C1 — 예외 없이 정상 반환, 커밋됨)
        return PaymentResult.declined(payment, snapshot.orderNumber(),
                authResult.failureCode(), authResult.failureReason());
    }

    /**
     * 연락처 해석 또는 409 예외 발생.
     *
     * <p>Mi1: payment 모듈이 {@code member.spi.MemberDirectory.findContactByUserId}로 직접 조회.
     * PG 호출 전 1회만 수행해 지역 변수로 보관. 해석 실패는 PG 호출 전에 드러난다.
     */
    private MemberContact resolveContactOrThrow(long userId) {
        try {
            return memberDirectory.findContactByUserId(userId);
        } catch (IllegalStateException e) {
            log.warn("결제 이벤트 연락처 해석 실패: userId={}", userId, e);
            throw new PaymentEventResolutionException("회원 연락처 해석에 실패했습니다.");
        }
    }

    /**
     * BigDecimal → long 변환 (P3).
     *
     * <p>소수부 0만 허용. 위반 시 {@link AmountConversionException}(500).
     * KRW는 소수 단위가 없으므로 DB {@code numeric(12,2)} 값의 소수부는 0(.00)이어야 한다.
     */
    private long toLongExact(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new AmountConversionException();
        }
    }

    /**
     * ready row 선점 또는 기존 row 재사용 (Ma1·Ma2 확장).
     *
     * <p>처리:
     * <ul>
     *   <li>기존 row 없음 → {@code Payment.create(...)} INSERT({@code saveAndFlush}).</li>
     *   <li>기존 row paid → 멱등 반환</li>
     *   <li>기존 row ready → 동일 row 재사용</li>
     *   <li>기존 row failed → 동일 row 재사용(Ma1 — 거절 후 재시도, 재승인/재거절 경로 진입)</li>
     *   <li>uq 위반({@code DataIntegrityViolationException}) → 재조회:
     *     <ul>
     *       <li>재조회 paid → 멱등 반환</li>
     *       <li>재조회 failed → 동일 {@code failed} row 재사용 경로 합류(Ma2 — NPE·미정의 동작 없음)</li>
     *       <li>재조회 ready → {@link PaymentInProgressException}(409)</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>Ma2 설명: 선점 승자가 거절해 row가 {@code failed}로 남은 뒤 패자가 재조회로
     * {@code failed} row를 만나는 케이스. 패자는 동일 {@code failed} row를 재사용하며
     * 상위 ⑥에서 PG 재호출한다. "한 주문에 대한 동시 PG 호출은 1건" 불변식은 유지된다
     * (패자가 PG에 도달하는 경우는 승자 커밋 후 직렬 경로뿐임 — 동시 도달 아님).
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
            if ("failed".equals(existing.getStatus())) {
                // 거절 후 재시도 — 동일 row 재사용(Ma1: 재승인/재거절 경로 진입)
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
            if (concurrent != null && "failed".equals(concurrent.getStatus())) {
                // Ma2: 선점 승자가 거절해 failed row가 남은 경우 — 동일 row 재사용 경로 합류
                // 패자는 승자 커밋 후 직렬 경로로 PG에 도달(동시 PG 호출 1건 불변식 유지)
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
     *
     * <p>017에서 거절 표현 추가:
     * <ul>
     *   <li>승인/멱등: {@code declined=false}</li>
     *   <li>거절: {@code declined=true}, {@code failureCode}/{@code failureReason} 포함</li>
     * </ul>
     *
     * <p>정적 팩토리 {@link #approved} / {@link #declined}으로 생성한다.
     * 호출부(ServiceResponse/FacadeImpl)는 {@code declined()}를 확인해 402 예외 변환 여부를 결정한다(C1·Ma3).
     */
    public record PaymentResult(
            Payment payment,
            String orderNumber,
            boolean declined,
            String failureCode,
            String failureReason
    ) {

        /**
         * 승인/멱등 결과 팩토리.
         *
         * @param payment     결제 entity
         * @param orderNumber 주문번호
         * @return declined=false 결과
         */
        public static PaymentResult approved(Payment payment, String orderNumber) {
            return new PaymentResult(payment, orderNumber, false, null, null);
        }

        /**
         * 거절 결과 팩토리.
         *
         * @param payment       결제 entity (status=failed)
         * @param orderNumber   주문번호
         * @param failureCode   실패 코드 (이벤트 페이로드·로그용)
         * @param failureReason 실패 사유 (사용자 노출 가능 메시지)
         * @return declined=true 결과
         */
        public static PaymentResult declined(
                Payment payment,
                String orderNumber,
                String failureCode,
                String failureReason
        ) {
            return new PaymentResult(payment, orderNumber, true, failureCode, failureReason);
        }
    }

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

    /**
     * 취소 처리 결과 (내부 타입).
     *
     * <p>정적 팩토리:
     * <ul>
     *   <li>{@link #cancelled} — 미결제 취소 성공 (orders=cancelled)</li>
     *   <li>{@link #refunded} — 결제완료 취소+환불 성공 (orders=refunded)</li>
     *   <li>{@link #already} — 멱등 재취소 (이미 cancelled/refunded)</li>
     * </ul>
     */
    public record CancelResult(
            long orderId,
            String orderNumber,
            String orderStatus,
            boolean isRefunded,
            long refundedAmount,
            String currency,
            boolean alreadyCancelled
    ) {

        /**
         * 미결제 취소 성공 결과.
         *
         * @param orderId     주문 PK
         * @param orderNumber 주문 번호
         * @param orderStatus 취소 후 주문 상태 ("cancelled")
         * @return 취소 결과
         */
        public static CancelResult cancelled(long orderId, String orderNumber, String orderStatus) {
            return new CancelResult(orderId, orderNumber, orderStatus, false, 0L, "KRW", false);
        }

        /**
         * 결제완료 취소+환불 성공 결과.
         *
         * @param orderId        주문 PK
         * @param orderNumber    주문 번호
         * @param orderStatus    취소 후 주문 상태 ("refunded")
         * @param refundedAmount 환불 금액 (long, KRW=원)
         * @param currency       통화 코드
         * @return 환불 결과
         */
        public static CancelResult refunded(long orderId, String orderNumber, String orderStatus,
                                            long refundedAmount, String currency) {
            return new CancelResult(orderId, orderNumber, orderStatus, true, refundedAmount, currency, false);
        }

        /**
         * 멱등 재취소 결과 (이미 cancelled/refunded).
         *
         * <p>B안(state 스냅샷 멱등): 동일 요청 → 동일 표현.
         * already-refunded 시 {@code refundedAmount/currency}를 스냅샷에서 채워 최초 {@link #refunded} 응답과 동일하게 맞춘다.
         * already-cancelled 시 {@code refundedAmount=0, currency=snapshot.currency()}로 최초 {@link #cancelled} 응답과 동일하게 맞춘다.
         *
         * @param orderId        주문 PK
         * @param orderNumber    주문 번호
         * @param orderStatus    현재 주문 상태 ("cancelled" 또는 "refunded")
         * @param refundedAmount 환불 금액 (refunded 시 finalAmount, cancelled 시 0)
         * @param currency       통화 코드 (스냅샷 값 사용)
         * @return 멱등 결과
         */
        public static CancelResult already(long orderId, String orderNumber, String orderStatus,
                                           long refundedAmount, String currency) {
            boolean wasRefunded = "refunded".equals(orderStatus);
            return new CancelResult(orderId, orderNumber, orderStatus, wasRefunded, refundedAmount, currency, true);
        }
    }
}
