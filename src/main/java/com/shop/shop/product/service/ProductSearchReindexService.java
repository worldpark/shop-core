package com.shop.shop.product.service;

import com.shop.shop.product.dto.ProductSearchSnapshotProjection;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.search.ProductSearchDocument;
import com.shop.shop.product.search.ProductSearchIndexAdmin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 상품 풀 재색인·백필 오케스트레이션 서비스 (T4 — 060).
 *
 * <p>PG 전량 상품을 keyset 페이지네이션으로 순회하며 ES 새 버전 인덱스에 bulk 색인하고,
 * 완료 후 alias를 새 인덱스로 원자적으로 swap한다. 실패 시 alias는 기존 인덱스를 유지한다.
 *
 * <p><b>비동기 실행 모델</b>:
 * <ul>
 *   <li>{@link #startAsync()} — 단일 스레드 executor에 잡을 submit하고 202를 반환한다.</li>
 *   <li>{@link #reindex()} — 동기 코어(순회→bulk→swap→정리). 통합 테스트가 직접 호출한다.</li>
 *   <li>{@link #status()} — 현재 잡 상태 조회.</li>
 * </ul>
 *
 * <p><b>중복 실행 가드</b>: {@code running} {@link AtomicBoolean} CAS.
 * CAS-true 성공 시만 잡을 submit한다. submit 자체가 실패하면 catch에서 {@code running=false}로 복원
 * (running이 true 고착되지 않도록 — 결정 2 가드 리셋 안전).
 *
 * <p><b>ES 비활성 게이팅</b>: {@link ProductSearchIndexAdmin}은 {@link ObjectProvider}로 주입된다.
 * ES가 비활성({@code shop.search.indexer.enabled=false})이면 admin 빈이 없으므로 ObjectProvider가
 * empty를 반환한다. 이 경우 {@link #reindex()}는 명확한 "검색 엔진 비활성" 예외를 던진다.
 * 트리거 엔드포인트(컨트롤러/ServiceResponse)는 ES 의존 없이 항상 배선된다.
 *
 * <p><b>자원 수명</b>: {@link DisposableBean#destroy()}에서 executor를 shutdown해
 * 컨텍스트 종료 시 스레드 누수를 차단한다(inapp-consumer-external-engine-rule §2).
 */
@Slf4j
@Service
public class ProductSearchReindexService implements DisposableBean {

    private final ObjectProvider<ProductSearchIndexAdmin> adminProvider;
    private final ProductRepository productRepository;
    private final int batchSize;
    private final long throttleMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ReindexStatus> statusRef =
            new AtomicReference<>(ReindexStatus.idle());

    /** 단일 데몬 스레드 executor — 재색인 잡 전용. */
    private final ExecutorService executor;

    public ProductSearchReindexService(
            ObjectProvider<ProductSearchIndexAdmin> adminProvider,
            ProductRepository productRepository,
            @Value("${shop.search.reindex.batch-size:500}") int batchSize,
            @Value("${shop.search.reindex.throttle-ms:0}") long throttleMs) {
        this.adminProvider = adminProvider;
        this.productRepository = productRepository;
        this.batchSize = batchSize;
        this.throttleMs = throttleMs;

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "search-reindex");
            t.setDaemon(true);
            return t;
        });
    }

    // ========================================================================
    // 비동기 트리거
    // ========================================================================

    /**
     * 재색인 잡을 비동기로 시작한다.
     *
     * <p>이미 진행 중({@code running=true})이면 현재 상태를 반환하고 잡을 시작하지 않는다(중복 실행 거부).
     * {@code running} CAS-true 직후 {@code executor.submit}이 실패하면 {@code running=false}로 복원한다.
     *
     * @return 현재 잡 상태 ({@code RUNNING} — 시작됨, 또는 이미 RUNNING — 중복 거부)
     * @throws IllegalStateException 이미 실행 중인 경우 (호출자가 409 응답 변환)
     */
    public ReindexStatus startAsync() {
        if (!running.compareAndSet(false, true)) {
            log.info("[Reindex] Already running. Rejecting duplicate trigger.");
            throw new IllegalStateException("재색인 잡이 이미 실행 중입니다.");
        }

        Instant startedAt = Instant.now();
        statusRef.set(ReindexStatus.running(startedAt));

        try {
            executor.submit(() -> {
                try {
                    reindex();
                } finally {
                    running.set(false);
                }
            });
        } catch (Exception e) {
            // submit 실패 (예: RejectedExecutionException — executor shutdown 후 트리거 시도 등)
            running.set(false);
            ReindexStatus failed = ReindexStatus.failed(startedAt, Instant.now(), 0L,
                    "executor submit failed: " + e.getMessage());
            statusRef.set(failed);
            log.error("[Reindex] executor.submit failed — running reset to false.", e);
            throw new IllegalStateException("재색인 잡 submit 실패: " + e.getMessage(), e);
        }

        log.info("[Reindex] Async reindex started.");
        return statusRef.get();
    }

    /**
     * 현재 잡 상태를 반환한다.
     *
     * @return 현재 {@link ReindexStatus}
     */
    public ReindexStatus status() {
        return statusRef.get();
    }

    // ========================================================================
    // 동기 코어
    // ========================================================================

    /**
     * 동기 재색인 코어: PG 전량 → bulk → 새 인덱스 → alias swap → 정리.
     *
     * <p>이 메서드는 트리거 스레드가 아닌 executor 스레드(또는 통합 테스트)에서 직접 호출된다.
     * 성공 시 {@code statusRef}를 SUCCESS로, 실패 시 FAILED로 업데이트한다.
     * {@code @Transactional} 없음 — 잡 전체가 단일 트랜잭션이면 장시간 트랜잭션이 된다.
     * 각 배치 read는 Spring Data의 짧은 read-only 트랜잭션(기본 동작)을 그대로 사용한다.
     *
     * <p>ES 비활성(admin 빈 없음)이면 명확한 예외를 던진다.
     */
    public void reindex() {
        ProductSearchIndexAdmin admin = adminProvider.getIfAvailable();
        if (admin == null) {
            String errorMsg = "검색 엔진 비활성 — shop.search.indexer.enabled=true 설정 필요";
            Instant now = Instant.now();
            statusRef.set(ReindexStatus.failed(now, now, 0L, errorMsg));
            throw new IllegalStateException(errorMsg);
        }

        Instant startedAt = Instant.now();
        if (statusRef.get().getState() != ReindexStatus.State.RUNNING) {
            statusRef.set(ReindexStatus.running(startedAt));
        } else {
            startedAt = statusRef.get().getStartedAt();
        }

        String newIndex = "products-v" + startedAt.toEpochMilli();
        long processedCount = 0L;

        log.info("[Reindex] Starting full reindex. newIndex={}, batchSize={}", newIndex, batchSize);

        try {
            // 1. 새 인덱스 생성
            admin.createIndex(newIndex);
            log.info("[Reindex] Created new index '{}'.", newIndex);

            // 2. keyset 순회 → bulk 색인
            long lastId = 0L;
            while (true) {
                List<ProductSearchSnapshotProjection> batch =
                        productRepository.findSearchSnapshotsAfter(lastId, Limit.of(batchSize));

                if (batch.isEmpty()) {
                    log.info("[Reindex] keyset traversal complete. processed={}", processedCount);
                    break;
                }

                List<ProductSearchDocument> docs = batch.stream()
                        .map(ProductSearchDocument::from)
                        .collect(Collectors.toList());

                admin.bulkIndex(newIndex, docs);

                lastId = batch.get(batch.size() - 1).productId();
                processedCount += batch.size();

                // 진행 상태 갱신
                final long currentCount = processedCount;
                final Instant sa = startedAt;
                statusRef.updateAndGet(s -> ReindexStatus.running(sa).withProcessed(currentCount));

                log.info("[Reindex] progress: processed={}, lastId={}", processedCount, lastId);

                // 스로틀 (기본 0ms)
                if (throttleMs > 0) {
                    Thread.sleep(throttleMs);
                }
            }

            // 3. alias swap (전량 성공 후에만)
            admin.pointAliasTo(newIndex);
            log.info("[Reindex] Alias swapped to '{}'.", newIndex);

            // 4. 정리 (현재 + 직전 1개 보존)
            cleanupOldIndices(admin, newIndex);

            // 5. 성공 상태 기록
            ReindexStatus success = ReindexStatus.success(startedAt, Instant.now(), processedCount, newIndex);
            statusRef.set(success);
            log.info("[Reindex] Completed. processed={}, newIndex={}", processedCount, newIndex);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ReindexStatus failed = ReindexStatus.failed(startedAt, Instant.now(), processedCount,
                    "interrupted: " + ie.getMessage());
            statusRef.set(failed);
            log.error("[Reindex] Interrupted. processed={}", processedCount, ie);

        } catch (Exception e) {
            ReindexStatus failed = ReindexStatus.failed(startedAt, Instant.now(), processedCount,
                    e.getMessage());
            statusRef.set(failed);
            log.error("[Reindex] Failed. processed={}, error={}", processedCount, e.getMessage(), e);
        }
    }

    // ========================================================================
    // 정리
    // ========================================================================

    /**
     * 오래된 {@code products-v*} 인덱스를 정리한다.
     *
     * <p>현재(새 인덱스) + 직전 1개를 creation_date 내림차순으로 보존하고 나머지를 삭제한다.
     * 정리 실패는 WARN 로깅하되 잡 실패로 보지 않는다(이미 alias swap 성공).
     *
     * @param admin    ES admin
     * @param newIndex alias swap이 완료된 현재 인덱스
     */
    private void cleanupOldIndices(ProductSearchIndexAdmin admin, String newIndex) {
        try {
            List<Map.Entry<String, Long>> indices = admin.listVersionIndicesWithCreationDate();
            // 최신 2개(현재 + 직전 1개) 보존
            int keepCount = 2;
            if (indices.size() <= keepCount) {
                log.debug("[Reindex] Cleanup: only {} index(es) — nothing to delete.", indices.size());
                return;
            }

            List<Map.Entry<String, Long>> toDelete = indices.subList(keepCount, indices.size());
            for (Map.Entry<String, Long> entry : toDelete) {
                String indexToDelete = entry.getKey();
                try {
                    admin.deleteIndex(indexToDelete);
                    log.info("[Reindex] Cleanup: deleted old index '{}'.", indexToDelete);
                } catch (Exception ex) {
                    log.warn("[Reindex] Cleanup: failed to delete index '{}' — WARN only. error={}",
                            indexToDelete, ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[Reindex] Cleanup: listVersionIndices failed — WARN only. error={}", e.getMessage());
        }
    }

    // ========================================================================
    // 자원 수명
    // ========================================================================

    /**
     * 컨텍스트 종료 시 executor를 shutdown해 스레드 누수를 차단한다.
     *
     * <p>inapp-consumer-external-engine-rule §2 준수.
     */
    @Override
    public void destroy() {
        log.info("[Reindex] Shutting down executor (context destroy).");
        executor.shutdownNow();
    }
}
