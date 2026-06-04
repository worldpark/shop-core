/**
 * platform 모듈 — 서비스 레이어.
 *
 * <p>두 가지 서비스 계층을 포함한다:
 * <ul>
 *   <li>{@link com.shop.shop.platform.service.DummyEventPublishService} —
 *       더미 이벤트 생성 및 {@code @Transactional} 범위 내 발행 담당.</li>
 *   <li>{@link com.shop.shop.platform.service.DummyEventServiceResponse} —
 *       REST 응답 조합 전용 계층(ServiceResponse 역할). View/Scheduler/EventListener에서 사용 금지.</li>
 * </ul>
 *
 * <p>가드레일:
 * <ul>
 *   <li>발행은 반드시 {@code @Transactional} 안에서 수행한다.</li>
 *   <li>모든 예외는 {@code RuntimeException} 상속 커스텀 예외로 변환한다.</li>
 *   <li>도메인 모듈을 import하지 않는다.</li>
 * </ul>
 */
package com.shop.shop.platform.service;
