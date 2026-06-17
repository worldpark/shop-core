package com.shop.shop.web.admin;

import com.shop.shop.web.admin.dto.AdminDashboardView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 관리자 대시보드 SSE 주기 broadcaster.
 *
 * <p>{@link AdminDashboardSseSchedulingConfig}가 로드될 때만 {@code @Scheduled}가 발화한다.
 * {@code shop.admin.dashboard.sse.enabled=false}(test 포함)이면 해당 설정이 미로드되어 스케줄이 발화하지 않는다.
 *
 * <p>활성 emitter가 0이면 {@link AdminDashboardAssembler#build()} 호출을 건너뛴다(불필요한 집계 쿼리 방지).
 * 활성 emitter가 있으면 현재 통계 스냅샷을 집계해 전체 emitter에 전송한다.
 *
 * <p>레이어: Scheduler → assembler(web 조합 컴포넌트) — ServiceResponse 미사용(architecture-rule).
 * {@code ThreadLocal} 직접 사용 금지(가상스레드 대비 — CLAUDE.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminDashboardSseBroadcaster {

    private final AdminDashboardSseRegistry registry;
    private final AdminDashboardAssembler assembler;

    /**
     * 주기적으로 대시보드 통계를 집계해 연결된 admin에게 push한다.
     *
     * <p>기본 interval: 10초({@code PT10S}). 환경변수 {@code shop.admin.dashboard.sse.interval}로 조정 가능.
     * 활성 emitter가 0이면 집계를 건너뛴다(빈 연결 최적화).
     */
    @Scheduled(fixedDelayString = "${shop.admin.dashboard.sse.interval:PT10S}")
    public void broadcast() {
        if (registry.activeCount() == 0) {
            log.trace("[SSE] no active emitters — skip assembler.build()");
            return;
        }

        log.debug("[SSE] broadcasting to {} active emitter(s)", registry.activeCount());
        AdminDashboardView view = assembler.build();
        registry.broadcast(view);
    }
}
