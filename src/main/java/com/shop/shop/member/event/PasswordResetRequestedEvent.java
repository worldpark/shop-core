package com.shop.shop.member.event;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

/**
 * 비밀번호 재설정 요청 이벤트 — Transactional Outbox로 Kafka {@code password-reset-requested} 토픽에 외부화된다.
 *
 * <p>발행 소유: member 모듈 (package-structure-rule: PasswordResetRequestedEvent는 member/event 소유).
 * 발행 경로: {@code PasswordResetService.requestReset}이 {@code ApplicationEventPublisher.publishEvent(event)}를
 * {@code @Transactional} 안에서 호출 → Spring Modulith Event Publication Registry가
 * {@code event_publication} INCOMPLETE 저장(Outbox) → 커밋 후 Kafka 외부화.
 *
 * <p>이벤트 계약 SSOT: docs/event-catalog.md(PasswordResetRequestedEvent) — 무변경.
 * 페이로드는 자족적(컨슈머가 shop-core를 역조회하지 않도록).
 * 민감정보(비밀번호/해시) 필드 금지. resetUrl은 메일 전달 목적 예외 명시.
 *
 * <p><b>Outbox 한계 위험</b>: {@code resetUrl}에 평문 토큰이 담겨 {@code event_publication.serialized_event}에
 * 잔존하나, 유효성은 Redis 키로만 판정(TTL 30분·1회용)되어 만료/사용 시 redeem 불가한 죽은 값이 된다.
 * 전역 Outbox 정리 정책 무변경(docs/plans/revisions/backend/030-backend-shop-core-password-reset-outbox-token-retention-revision-1.md 근거).
 *
 * @param eventId     이벤트 고유 식별자 (컨슈머 멱등 키)
 * @param occurredAt  이벤트 발생 시각 ({@code Instant.now()}, 커밋 직전)
 * @param memberId    회원 PK
 * @param memberEmail 재설정 메일 수신 이메일
 * @param memberName  수신자 이름
 * @param resetUrl    비밀번호 재설정 링크(토큰 포함, 메일 전달 목적 한정 — 로그 기록 금지)
 * @param expiresAt   토큰 만료 시각 (발행 시각 + TTL)
 */
@Externalized("password-reset-requested")
public record PasswordResetRequestedEvent(
        UUID eventId,
        Instant occurredAt,
        long memberId,
        String memberEmail,
        String memberName,
        String resetUrl,
        Instant expiresAt
) {

    /** 외부화 대상 Kafka 토픽명. MemberRegisteredEvent 선례와 동일 패턴. */
    public static final String TOPIC = "password-reset-requested";
}
