/**
 * platform 모듈 — Transactional Outbox 스모크 검증 전용 한시 모듈.
 *
 * <p>이 모듈은 Spring Modulith Event Publication Registry 기반 Kafka 외부화 경로를
 * 도메인 기능 구현 전에 조기 검증(smoke test)하기 위한 인프라성 임시 모듈이다.
 * 스모크 검증이 완료된 이후 제거 또는 교체 대상이다.
 *
 * <p><b>가드레일 (도메인 모듈 비참조):</b>
 * <ul>
 *   <li>이 모듈은 {@code member / product / cart / order / payment / inventory} 등
 *       도메인 모듈을 직접 import하지 않는다.</li>
 *   <li>횡단 공유 모듈인 {@code common}(OPEN 모듈) 의존만 허용한다.</li>
 *   <li>이 제약을 위반하면 {@code ModularityTests.verify()} 가 실패한다.</li>
 * </ul>
 *
 * <p>발행 토픽: {@code shop-core-smoke-test} — 공개 이벤트 계약(docs/architecture.md 섹션 5) 외 토픽이며
 * notification 서비스가 구독하지 않는다.
 */
package com.shop.shop.platform;
