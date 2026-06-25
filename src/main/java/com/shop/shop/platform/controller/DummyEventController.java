package com.shop.shop.platform.controller;

import com.shop.shop.platform.dto.DummyEventPublishResponse;
import com.shop.shop.platform.service.DummyEventServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transactional Outbox 스모크 검증 REST 진입점.
 *
 * <p>이 컨트롤러는 로직 없이 {@link DummyEventServiceResponse}에 위임만 한다.
 * 비즈니스 로직 작성 금지(CLAUDE.md 가드레일).
 *
 * <p>엔드포인트: {@code POST /api/v1/platform/smoke/events}
 */
@Tag(name = "platform", description = "플랫폼 — Transactional Outbox 스모크 검증")
@RestController
@RequestMapping("/api/v1/platform/smoke")
@RequiredArgsConstructor
public class DummyEventController {

    private final DummyEventServiceResponse dummyEventServiceResponse;

    /**
     * 더미 이벤트를 발행하고 발행 결과를 반환한다.
     *
     * @param message 발행 메시지 (optional, 기본값: "smoke-test")
     * @return 발행된 이벤트 정보 DTO
     */
    @Operation(summary = "더미 이벤트 발행 — Outbox 스모크 검증")
    @PostMapping("/events")
    public ResponseEntity<DummyEventPublishResponse> publishSmokeEvent(
            @RequestParam(required = false, defaultValue = "smoke-test") String message) {
        DummyEventPublishResponse response = dummyEventServiceResponse.publishDummy(message);
        return ResponseEntity.ok(response);
    }
}
