package com.shop.shop.product.dto;

import com.shop.shop.product.service.ReindexStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 재색인 잡 상태 응답 DTO.
 *
 * <p>REST API 응답({@code GET /api/v1/admin/products/search-index/status},
 * {@code POST .../reindex} 202 본문)으로 사용한다.
 *
 * <p>Entity를 직접 노출하지 않는 DTO 규칙을 준수한다.
 * 정적 팩토리 {@link #from(ReindexStatus)}으로 생성한다.
 */
@Getter
@Builder
public class ReindexStatusResponse {

    /** 잡 상태 문자열: IDLE / RUNNING / SUCCESS / FAILED */
    private final String state;

    /** 잡 시작 시각 (null이면 미시작) */
    private final Instant startedAt;

    /** 잡 완료 시각 (null이면 미완료) */
    private final Instant finishedAt;

    /** 처리 완료 문서 수 */
    private final long processedCount;

    /** 새로 생성·활성화된 인덱스 이름 (성공 시) */
    private final String newIndex;

    /** 오류 메시지 (실패 시) */
    private final String errorMessage;

    /**
     * 내부 상태 객체에서 REST 응답 DTO를 생성한다.
     *
     * @param status 내부 재색인 상태
     * @return REST 응답 DTO
     */
    public static ReindexStatusResponse from(ReindexStatus status) {
        return ReindexStatusResponse.builder()
                .state(status.getState().name())
                .startedAt(status.getStartedAt())
                .finishedAt(status.getFinishedAt())
                .processedCount(status.getProcessedCount())
                .newIndex(status.getNewIndex())
                .errorMessage(status.getErrorMessage())
                .build();
    }
}
