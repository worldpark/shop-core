package com.shop.shop.web.admin;

import com.shop.shop.web.admin.dto.AdminDashboardView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminDashboardSseBroadcaster} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>활성 emitter 0이면 assembler.build() 미호출(skip)</li>
 *   <li>활성 emitter 있으면 assembler.build() 호출 + registry.broadcast() 호출</li>
 * </ul>
 *
 * <p>스케줄 발화 자체는 검증하지 않는다 — 로직을 broadcast() 직접 호출로 검증.
 * (test 프로파일에서 {@code AdminDashboardSseSchedulingConfig}가 미로드되어 @Scheduled 미발화)
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardSseBroadcasterTest {

    @Mock
    private AdminDashboardSseRegistry registry;

    @Mock
    private AdminDashboardAssembler assembler;

    @InjectMocks
    private AdminDashboardSseBroadcaster broadcaster;

    private AdminDashboardView stubView;

    @BeforeEach
    void setUp() {
        AdminDashboardView.Metric m = new AdminDashboardView.Metric(new BigDecimal("30.0"), 30L, 100L);
        stubView = new AdminDashboardView(m, m, m, "최근 30일");
    }

    // ============================================================
    // 활성 emitter 0이면 skip
    // ============================================================

    @Test
    @DisplayName("활성 emitter 0이면 assembler.build() 미호출(빈 연결 최적화 skip)")
    void broadcast_no_active_emitters_skips_assembler_build() {
        when(registry.activeCount()).thenReturn(0);

        broadcaster.broadcast();

        verify(assembler, never()).build();
        verify(registry, never()).broadcast(stubView);
    }

    // ============================================================
    // 활성 emitter 있으면 집계 + broadcast
    // ============================================================

    @Test
    @DisplayName("활성 emitter 있으면 assembler.build() 호출 후 registry.broadcast() 전달")
    void broadcast_with_active_emitters_calls_assembler_and_registry() {
        when(registry.activeCount()).thenReturn(2);
        when(assembler.build()).thenReturn(stubView);

        broadcaster.broadcast();

        verify(assembler).build();
        verify(registry).broadcast(stubView);
    }

    @Test
    @DisplayName("활성 emitter 1이면 assembler.build() 1회 호출")
    void broadcast_single_active_emitter_calls_assembler_once() {
        when(registry.activeCount()).thenReturn(1);
        when(assembler.build()).thenReturn(stubView);

        broadcaster.broadcast();

        verify(assembler).build();
    }
}
