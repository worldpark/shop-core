package com.shop.shop.member.event;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

/**
 * 회원가입 확정 이벤트 — Transactional Outbox로 Kafka {@code member-registered} 토픽에 외부화된다.
 *
 * <p>발행 소유: member 모듈 (package-structure-rule: MemberRegisteredEvent는 member/event 소유).
 * 발행 경로: {@code MemberService.signup}이 {@code ApplicationEventPublisher.publishEvent(event)}를
 * {@code @Transactional} 안에서 호출 → Spring Modulith Event Publication Registry가
 * {@code event_publication} INCOMPLETE 저장(Outbox) → 커밋 후 Kafka 외부화.
 *
 * <p>이벤트 계약 SSOT: docs/event-catalog.md(MemberRegisteredEvent) — 무변경.
 * 페이로드는 자족적(컨슈머가 shop-core를 역조회하지 않도록).
 * 민감정보(비밀번호/해시/토큰) 필드 금지.
 *
 * @param eventId     이벤트 고유 식별자 (컨슈머 멱등 키)
 * @param occurredAt  이벤트 발생 시각 ({@code Instant.now()}, 커밋 직전)
 * @param memberId    회원 PK
 * @param memberEmail 환영 메일 수신 이메일
 * @param memberName  수신자 이름
 */
@Externalized("member-registered")
public record MemberRegisteredEvent(
        UUID eventId,
        Instant occurredAt,
        long memberId,
        String memberEmail,
        String memberName
) {

    /** 외부화 대상 Kafka 토픽명. OrderCompletedEvent 선례와 동일 패턴. */
    public static final String TOPIC = "member-registered";
}
