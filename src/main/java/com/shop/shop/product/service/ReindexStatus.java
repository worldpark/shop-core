package com.shop.shop.product.service;

import lombok.Getter;

import java.time.Instant;

/**
 * 재색인 잡 내부 상태 추적 객체.
 *
 * <p>{@link ProductSearchReindexService}의 {@code AtomicReference<ReindexStatus>}로 관리된다.
 * 불변 팩토리 메서드로 상태 전환을 표현한다(setter 없음).
 *
 * <p>이 객체는 product 모듈 내부 전용이다. REST 응답 변환은
 * {@link com.shop.shop.product.dto.ReindexStatusResponse}가 담당한다.
 */
@Getter
public final class ReindexStatus {

    /**
     * 재색인 잡 상태 열거.
     */
    public enum State {
        /** 잡이 한 번도 실행되지 않은 초기 상태 또는 이전 잡 완료 후 대기 중 */
        IDLE,
        /** 잡이 현재 실행 중 */
        RUNNING,
        /** 잡이 성공적으로 완료됨 */
        SUCCESS,
        /** 잡이 실패함 */
        FAILED
    }

    private final State state;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final long processedCount;
    private final String newIndex;
    private final String errorMessage;

    private ReindexStatus(State state, Instant startedAt, Instant finishedAt,
                          long processedCount, String newIndex, String errorMessage) {
        this.state = state;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.processedCount = processedCount;
        this.newIndex = newIndex;
        this.errorMessage = errorMessage;
    }

    /** 초기 IDLE 상태 */
    public static ReindexStatus idle() {
        return new ReindexStatus(State.IDLE, null, null, 0L, null, null);
    }

    /** 잡 시작 — RUNNING 상태 */
    public static ReindexStatus running(Instant startedAt) {
        return new ReindexStatus(State.RUNNING, startedAt, null, 0L, null, null);
    }

    /** 진행 중 처리 건수 갱신 */
    public ReindexStatus withProcessed(long count) {
        return new ReindexStatus(state, startedAt, finishedAt, count, newIndex, errorMessage);
    }

    /** 잡 성공 완료 */
    public static ReindexStatus success(Instant startedAt, Instant finishedAt,
                                        long processedCount, String newIndex) {
        return new ReindexStatus(State.SUCCESS, startedAt, finishedAt, processedCount, newIndex, null);
    }

    /** 잡 실패 */
    public static ReindexStatus failed(Instant startedAt, Instant finishedAt,
                                       long processedCount, String errorMessage) {
        return new ReindexStatus(State.FAILED, startedAt, finishedAt, processedCount, null, errorMessage);
    }
}
