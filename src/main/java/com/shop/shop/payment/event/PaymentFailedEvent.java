package com.shop.shop.payment.event;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

/**
 * 결제 거절 도메인 이벤트 (payment 모듈 발행 소유).
 *
 * <p>topic: {@code payment-failed} ({@code @Externalized} Kafka 외부화).
 * Spring Modulith Transactional Outbox로 발행: {@code PaymentService.pay}가
 * 동일 트랜잭션 안에서 {@code ApplicationEventPublisher.publishEvent}를 호출 →
 * {@code event_publication} INCOMPLETE 저장 → 커밋 후 {@code spring-modulith-events-kafka}가 외부화.
 *
 * <p>페이로드는 자족적으로 구성한다(컨슈머가 shop-core를 재조회하지 않도록, event-contract-rule).
 * {@code memberEmail}/{@code memberName}은 payment 모듈이 {@code member.spi.MemberDirectory.findContactByUserId}로
 * 직접 조회해 채운다(Mi1).
 *
 * <p>페이로드 스키마는 {@code docs/event-catalog.md}의 PaymentFailedEvent 계약을 그대로 따른다.
 * 계약 변경 시 event-catalog.md를 먼저 수정한다.
 *
 * <p>이벤트 소유권: payment 모듈. {@code OrderCompletedEvent}는 order 모듈 소유와 분리(package-structure-rule).
 *
 * @param eventId       이벤트 고유 식별자 (컨슈머 멱등 키)
 * @param occurredAt    이벤트 발생 시각
 * @param orderId       주문 PK
 * @param orderNumber   사용자 노출용 주문번호
 * @param memberId      회원 PK (=주문 userId)
 * @param memberEmail   알림 수신 이메일 (member.spi 직접 조회, Mi1)
 * @param memberName    수신자 이름 (member.spi 직접 조회, Mi1)
 * @param amount        결제 시도 금액 (long, finalAmount longValueExact 변환, P3)
 * @param currency      통화 코드 (snapshot.currency() — 하드코딩 금지, 모순5)
 * @param failureCode   실패 코드 (예: CARD_DECLINED, notification 분류용)
 * @param failureReason 실패 사유 (사람이 읽는 메시지, 사용자 노출 가능)
 * @param attemptedAt   결제 시도 시각
 */
@Externalized("payment-failed")
public record PaymentFailedEvent(
        UUID eventId,
        Instant occurredAt,
        long orderId,
        String orderNumber,
        long memberId,
        String memberEmail,
        String memberName,
        long amount,
        String currency,
        String failureCode,
        String failureReason,
        Instant attemptedAt
) {

    /** Kafka 토픽 이름 상수 (DummyOutboxSmokeEvent/OrderCompletedEvent 선례). */
    public static final String TOPIC = "payment-failed";

    /**
     * 정적 팩토리 — eventId/occurredAt을 자동으로 채운다.
     *
     * @param orderId       주문 PK
     * @param orderNumber   주문번호
     * @param memberId      회원 PK
     * @param memberEmail   알림 수신 이메일
     * @param memberName    수신자 이름
     * @param amount        결제 시도 금액 (long)
     * @param currency      통화 코드
     * @param failureCode   실패 코드
     * @param failureReason 실패 사유
     * @param attemptedAt   결제 시도 시각
     * @return 새 PaymentFailedEvent 인스턴스
     */
    public static PaymentFailedEvent of(
            long orderId,
            String orderNumber,
            long memberId,
            String memberEmail,
            String memberName,
            long amount,
            String currency,
            String failureCode,
            String failureReason,
            Instant attemptedAt
    ) {
        return new PaymentFailedEvent(
                UUID.randomUUID(),
                Instant.now(),
                orderId,
                orderNumber,
                memberId,
                memberEmail,
                memberName,
                amount,
                currency,
                failureCode,
                failureReason,
                attemptedAt
        );
    }
}
