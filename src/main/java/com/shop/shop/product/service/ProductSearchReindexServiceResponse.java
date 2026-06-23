package com.shop.shop.product.service;

import com.shop.shop.product.dto.ReindexStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 재색인 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link ProductSearchReindexService}에 전적으로 위임.
 *
 * <p>레이어: AdminProductSearchReindexRestController → ProductSearchReindexServiceResponse
 * → ProductSearchReindexService
 */
@Service
@RequiredArgsConstructor
public class ProductSearchReindexServiceResponse {

    private final ProductSearchReindexService reindexService;

    /**
     * 재색인 잡을 비동기로 시작한다.
     *
     * <p>이미 진행 중이면 {@link IllegalStateException}을 던진다(호출자가 409 응답 변환).
     *
     * @return 현재 잡 상태 DTO (RUNNING 상태)
     * @throws IllegalStateException 이미 실행 중인 경우
     */
    public ReindexStatusResponse startReindex() {
        ReindexStatus status = reindexService.startAsync();
        return ReindexStatusResponse.from(status);
    }

    /**
     * 현재 재색인 잡 상태를 조회한다.
     *
     * @return 현재 잡 상태 DTO
     */
    public ReindexStatusResponse getStatus() {
        return ReindexStatusResponse.from(reindexService.status());
    }
}
