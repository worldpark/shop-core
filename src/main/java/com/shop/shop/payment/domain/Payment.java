package com.shop.shop.payment.domain;

import com.shop.shop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 Entity.
 *
 * <p>테이블: payments (V1__init_schema.sql line 314~334).
 * 신규 migration 불필요 — V1이 이미 전 필드를 포함한다.
 *
 * <p>orderId 스칼라: order Entity 직접 참조 금지(architecture-rule 모듈 경계).
 * status: "ready"/"paid"/"failed"/"cancelled"/"refunded".
 * - "ready": 결제 시도 중(PG 호출 전 선점)
 * - "paid": 결제 승인 완료
 * - "failed": 결제 거절(017에서 markFailed 추가)
 * method: "card"/"bank_transfer"/"virtual_account"/"mock" — 016 기본 "mock". (DB CHECK 허용값)
 * amount: BigDecimal(precision=12, scale=2) — 저장 정밀도 보존.
 * paidAt/pgTransactionId: nullable, 승인 시 기록.
 * created_at/updated_at: DB 트리거 소유 → BaseEntity 상속(읽기전용 매핑).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} + 의도 메서드 {@link #markPaid}, {@link #markFailed}.
 *
 * <p>상태 전이:
 * <ul>
 *   <li>ready → paid: {@link #markPaid} (016 승인 경로)</li>
 *   <li>failed → paid: {@link #markPaid} (017 재시도 승인, Ma1)</li>
 *   <li>paid 재호출: {@link #markPaid} 멱등</li>
 *   <li>ready → failed: {@link #markFailed} (017 거절 경로)</li>
 *   <li>failed → failed: {@link #markFailed} 멱등(재거절 no-op)</li>
 *   <li>paid → failed: 금지 — {@link #markFailed}에서 IllegalStateException</li>
 * </ul>
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소유자 주문 ID 스칼라.
     * payment → order Entity 직접 참조 금지 — Long 스칼라로 보유.
     * FK 무결성은 DB(REFERENCES orders(id) ON DELETE RESTRICT)가 보장.
     * UNIQUE(order_id): uq_payments_order_id — 주문당 결제 1건, 동시 결제 직렬화(#2).
     */
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    /**
     * 결제 수단.
     * DB CHECK: IN('card','bank_transfer','virtual_account','mock').
     * 016 기본: "mock".
     */
    @Column(nullable = false, length = 20)
    private String method;

    /**
     * 결제 상태 (DB lowercase 문자열 — enum 아님).
     * 016에서 "ready"/"paid"만 사용.
     * "failed"는 017에서 markFailed() 추가.
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * 결제 금액 (BigDecimal, precision=12, scale=2).
     * 서버 권위 finalAmount로 채운다 — 클라이언트 금액 미신뢰.
     * 이벤트 페이로드 직렬화 시점에만 long 변환(저장 정밀도와 계약 타입 분리, P3).
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * PG 거래 번호 (nullable, 승인 시 기록).
     */
    @Column(name = "pg_transaction_id")
    private String pgTransactionId;

    /**
     * 결제 완료 시각 (nullable, 승인 시 기록).
     */
    @Column(name = "paid_at")
    private Instant paidAt;

    /**
     * 결제 row 생성 정적 팩토리 (status="ready").
     *
     * <p>INSERT 후 uq_payments_order_id 위반 감지로 동시 결제 직렬화(#2).
     * saveAndFlush를 사용해 INSERT를 즉시 flush해 unique 충돌이 트랜잭션 경계에서 드러나게 한다.
     *
     * @param orderId  주문 ID
     * @param method   결제 수단 (예: "mock")
     * @param amount   결제 금액 (서버 권위 finalAmount)
     * @return 새 Payment 인스턴스 (status="ready")
     */
    public static Payment create(long orderId, String method, BigDecimal amount) {
        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.method = method;
        payment.status = "ready";
        payment.amount = amount;
        return payment;
    }

    /**
     * 결제 승인 상태 전이 메서드 (ready/failed → paid).
     *
     * <p>016에서는 "ready→paid"만 허용. 017(Ma1)에서 "failed→paid" 전이를 추가 허용.
     * 거절 후 재시도 승인 경로: failed → paid.
     *
     * <ul>
     *   <li>"paid" 재호출 → 멱등(무시)</li>
     *   <li>"ready" 또는 "failed" → "paid" 전이</li>
     *   <li>그 외 → {@link IllegalStateException}</li>
     * </ul>
     *
     * <p>Ma1: failed→paid 전이 허용으로 거절 후 재시도 승인이 가능하다.
     * ready→paid(016 happy path)는 회귀 없이 유지된다(테스트로 보장).
     *
     * @param pgTransactionId PG 거래 번호
     * @param paidAt          결제 완료 시각
     * @throws IllegalStateException status가 "ready"/"failed"/"paid"가 아닐 때
     */
    public void markPaid(String pgTransactionId, Instant paidAt) {
        if ("paid".equals(this.status)) {
            // 멱등 처리 — 이미 paid면 무시
            return;
        }
        if (!"ready".equals(this.status) && !"failed".equals(this.status)) {
            throw new IllegalStateException(
                    "결제 상태가 ready 또는 failed가 아니어서 paid로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "paid";
        this.pgTransactionId = pgTransactionId;
        this.paidAt = paidAt;
    }

    /**
     * 결제 거절 상태 전이 메서드 (ready/failed → failed).
     *
     * <ul>
     *   <li>"ready" → "failed" 전이</li>
     *   <li>"failed" 재호출 → 멱등(상태 무변경 no-op, 재거절)</li>
     *   <li>"paid" → {@link IllegalStateException}(승인된 결제를 거절로 역전이 금지)</li>
     * </ul>
     *
     * <p><b>인자 현재 미영속·미사용(옵션 A)</b>: {@code failureCode}/{@code failureReason}을
     * Entity 컬럼에 영속하지 않는다({@code payments}에 전용 컬럼 없음, 신규 migration 불필요).
     * 이 인자들은 현재 메서드 본문에서 사용하지 않는다(상태 전이만 수행, 부작용 없음).
     * 거절 사유는 호출부({@link com.shop.shop.payment.service.PaymentService})가
     * 이벤트 페이로드({@code PaymentFailedEvent})·REST 402 응답에 직접 싣는다.
     *
     * <p><b>전이 의도·옵션 B 대비</b>: 인자를 받되 미사용인 것은 의도적 설계다.
     * 후속 Task에서 옵션 B(사유 영속)를 도입할 때 호출부·시그니처 변경 없이
     * 이 메서드 본문에 {@code this.failureCode = failureCode} 한 줄만 추가하면 된다.
     *
     * @param failureCode   실패 코드 (현재 미영속 — 옵션 A)
     * @param failureReason 실패 사유 (현재 미영속 — 옵션 A)
     * @throws IllegalStateException status가 "paid"일 때 (역전이 금지)
     */
    public void markFailed(String failureCode, String failureReason) {
        if ("paid".equals(this.status)) {
            throw new IllegalStateException(
                    "승인된 결제를 거절로 역전이할 수 없습니다. 현재 상태: " + this.status);
        }
        if ("failed".equals(this.status)) {
            // 멱등 처리 — 이미 failed면 no-op (재거절, 상태 무변경)
            return;
        }
        // ready → failed 전이
        this.status = "failed";
    }

    /**
     * 결제 취소 상태 전이 메서드 (ready/failed → cancelled, 미결제 취소 경로).
     *
     * <p>상태 전이표:
     * <ul>
     *   <li>"ready" → "cancelled" 전이</li>
     *   <li>"failed" → "cancelled" 전이</li>
     *   <li>"cancelled" 재호출 → 멱등(상태 무변경 no-op)</li>
     *   <li>"paid" → {@link IllegalStateException}(결제완료 취소는 환불을 거쳐야 함 — markRefunded 사용)</li>
     *   <li>"refunded" → {@link IllegalStateException}(이미 종결 상태)</li>
     * </ul>
     *
     * @throws IllegalStateException status가 "paid" 또는 "refunded"일 때
     */
    public void markCancelled() {
        if ("cancelled".equals(this.status)) {
            // 멱등 처리 — 이미 cancelled면 no-op
            return;
        }
        if ("paid".equals(this.status)) {
            throw new IllegalStateException(
                    "결제완료 상태에서 직접 cancelled로 전이할 수 없습니다. 환불(markRefunded)을 먼저 수행하세요. 현재 상태: " + this.status);
        }
        if ("refunded".equals(this.status)) {
            throw new IllegalStateException(
                    "이미 환불 완료된 결제를 cancelled로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        // ready/failed → cancelled 전이
        this.status = "cancelled";
    }

    /**
     * 결제 환불 완료 상태 전이 메서드 (paid → refunded, 결제완료 취소 경로).
     *
     * <p>상태 전이표:
     * <ul>
     *   <li>"paid" → "refunded" 전이</li>
     *   <li>"refunded" 재호출 → 멱등(상태 무변경 no-op)</li>
     *   <li>"ready"/"failed"/"cancelled" → {@link IllegalStateException}</li>
     * </ul>
     *
     * <p><b>pgRefundId 현재 미영속(옵션 A)</b>: {@code payments}에 {@code pg_refund_id} 컬럼이 없어
     * 신규 migration 없이 처리한다. 환불 ID/사유 영속이 필요하면 후속 Task(옵션 B).
     * 인자는 시그니처 고정 목적이며 현재 본문에서 미사용(markFailed 선례와 동형).
     *
     * @param pgRefundId PG 환불 번호 (현재 미영속 — 옵션 A)
     * @throws IllegalStateException status가 "paid"/"refunded"가 아닐 때
     */
    public void markRefunded(String pgRefundId) {
        if ("refunded".equals(this.status)) {
            // 멱등 처리 — 이미 refunded면 no-op
            return;
        }
        if (!"paid".equals(this.status)) {
            throw new IllegalStateException(
                    "결제 상태가 paid가 아니어서 refunded로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        // paid → refunded 전이 (pgRefundId 미영속 — 옵션 A)
        this.status = "refunded";
    }
}
