package com.shop.shop.product.controller;

import com.shop.shop.product.dto.ReindexStatusResponse;
import com.shop.shop.product.service.ProductSearchReindexServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN 상품 검색 인덱스 재색인 REST 컨트롤러 (T4 — 060).
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} (기존 매처).
 * 신규 matcher 불필요 — SecurityConfig 무변경.
 * 비ADMIN → 403, 비인증 → 401.
 * 비즈니스 로직 없음 — {@link ProductSearchReindexServiceResponse}에 위임.
 *
 * <p>이 컨트롤러는 ES 의존 없이 항상 배선된다. ES admin 빈 부재 여부 처리는
 * {@link com.shop.shop.product.service.ProductSearchReindexService}가 담당한다
 * ({@code ObjectProvider<ProductSearchIndexAdmin>}).
 */
@Tag(name = "admin-product", description = "관리자 상품 관리 — 검색 인덱스 재색인 (ADMIN 전용)")
@RestController
@RequestMapping("/api/v1/admin/products/search-index")
@RequiredArgsConstructor
public class AdminProductSearchReindexRestController {

    private final ProductSearchReindexServiceResponse reindexServiceResponse;

    /**
     * 풀 재색인 잡을 비동기로 시작한다.
     *
     * <p>잡이 즉시 시작되며 202 Accepted + 현재 상태 DTO를 반환한다.
     * 잡은 백그라운드 단일 스레드에서 실행된다(응답이 완주를 기다리지 않음).
     * 이미 진행 중이면 409 Conflict + 현재 상태 DTO를 반환한다.
     *
     * @return 202 Accepted (RUNNING) 또는 409 Conflict (already running)
     */
    @Operation(summary = "풀 재색인 잡 시작 (ADMIN)")
    @PostMapping("/reindex")
    public ResponseEntity<ReindexStatusResponse> reindex() {
        try {
            ReindexStatusResponse status = reindexServiceResponse.startReindex();
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
        } catch (IllegalStateException e) {
            // 이미 진행 중 (AtomicBoolean CAS 실패)
            ReindexStatusResponse status = reindexServiceResponse.getStatus();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(status);
        }
    }

    /**
     * 마지막/진행 중 재색인 잡 상태를 조회한다.
     *
     * @return 200 OK + 현재 잡 상태 DTO
     */
    @GetMapping("/status")
    public ResponseEntity<ReindexStatusResponse> status() {
        return ResponseEntity.ok(reindexServiceResponse.getStatus());
    }
}
