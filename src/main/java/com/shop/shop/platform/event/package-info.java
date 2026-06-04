/**
 * platform 모듈 — 이벤트 레이어.
 *
 * <p>Transactional Outbox 스모크 검증용 더미 이벤트 정의를 담는다.
 * {@link com.shop.shop.platform.event.DummyOutboxSmokeEvent}가 이 레이어의 유일한 이벤트이며,
 * {@code @Externalized("shop-core-smoke-test")}로 Kafka에 외부화된다.
 *
 * <p>공개 이벤트 계약(docs/architecture.md 섹션 5)과 완전히 분리된 스모크 전용 이벤트이므로
 * notification 서비스가 이 토픽을 구독하지 않는다.
 */
package com.shop.shop.platform.event;
