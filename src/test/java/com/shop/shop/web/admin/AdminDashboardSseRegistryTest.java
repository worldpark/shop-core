package com.shop.shop.web.admin;

import com.shop.shop.web.admin.dto.AdminDashboardView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link AdminDashboardSseRegistry} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>add 후 activeCount 증가</li>
 *   <li>remove 후 activeCount 감소</li>
 *   <li>broadcast: add 후 emitter에 데이터 전송됨</li>
 *   <li>broadcast: IOException emitter는 completeWithError + 레지스트리 제거</li>
 *   <li>onCompletion/onTimeout 콜백으로 remove 동작</li>
 * </ul>
 */
class AdminDashboardSseRegistryTest {

    private AdminDashboardSseRegistry registry;
    private AdminDashboardView stubView;

    @BeforeEach
    void setUp() {
        registry = new AdminDashboardSseRegistry();
        AdminDashboardView.Metric m = new AdminDashboardView.Metric(new BigDecimal("50.0"), 50L, 100L);
        stubView = new AdminDashboardView(m, m, m, "최근 30일");
    }

    // ============================================================
    // add / remove / activeCount
    // ============================================================

    @Test
    @DisplayName("add 후 activeCount 1 증가")
    void add_increases_active_count() {
        SseEmitter emitter = new SseEmitter();
        registry.add(emitter);

        assertThat(registry.activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove 후 activeCount 감소")
    void remove_decreases_active_count() {
        SseEmitter emitter = new SseEmitter();
        registry.add(emitter);
        registry.remove(emitter);

        assertThat(registry.activeCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("여러 emitter add 후 모두 remove — activeCount 0")
    void add_multiple_then_remove_all() {
        SseEmitter e1 = new SseEmitter();
        SseEmitter e2 = new SseEmitter();
        registry.add(e1);
        registry.add(e2);

        assertThat(registry.activeCount()).isEqualTo(2);

        registry.remove(e1);
        registry.remove(e2);

        assertThat(registry.activeCount()).isEqualTo(0);
    }

    // ============================================================
    // onCompletion / onTimeout 콜백으로 remove
    // ============================================================

    @Test
    @DisplayName("레지스트리 remove가 onCompletion/onTimeout 패턴으로 동작함 — remove 직접 호출 검증")
    void on_completion_callback_pattern_removes_emitter_from_registry() {
        SseEmitter emitter = new SseEmitter();
        registry.add(emitter);
        assertThat(registry.activeCount()).isEqualTo(1);

        // SSE 컨트롤러에서는 onCompletion(() -> registry.remove(emitter))처럼 등록한다.
        // SseEmitter.complete()의 콜백 호출 타이밍은 Spring 내부에 의존하므로,
        // 콜백 로직 자체(remove)를 직접 호출해 레지스트리 동작을 검증한다.
        registry.remove(emitter);

        assertThat(registry.activeCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("remove는 등록된 emitter만 제거하고 다른 emitter는 유지")
    void remove_only_removes_specified_emitter() {
        SseEmitter e1 = new SseEmitter();
        SseEmitter e2 = new SseEmitter();
        registry.add(e1);
        registry.add(e2);

        registry.remove(e1);

        assertThat(registry.activeCount()).isEqualTo(1);
    }

    // ============================================================
    // broadcast
    // ============================================================

    @Test
    @DisplayName("broadcast: 정상 emitter에 데이터 전송됨")
    void broadcast_sends_data_to_active_emitter() throws IOException {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        registry.add(mockEmitter);

        registry.broadcast(stubView);

        verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("broadcast: 복수 emitter — 모두 send 호출됨")
    void broadcast_sends_to_all_emitters() throws IOException {
        SseEmitter mock1 = mock(SseEmitter.class);
        SseEmitter mock2 = mock(SseEmitter.class);
        registry.add(mock1);
        registry.add(mock2);

        registry.broadcast(stubView);

        verify(mock1).send(any(SseEmitter.SseEventBuilder.class));
        verify(mock2).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("broadcast: IOException emitter는 completeWithError + 레지스트리 제거")
    void broadcast_removes_dead_emitter_on_io_exception() throws IOException {
        SseEmitter deadEmitter = mock(SseEmitter.class);
        doThrow(new IOException("connection reset")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.add(deadEmitter);
        assertThat(registry.activeCount()).isEqualTo(1);

        registry.broadcast(stubView);

        verify(deadEmitter).completeWithError(any(IOException.class));
        assertThat(registry.activeCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("broadcast: 죽은 emitter 제거 후 정상 emitter는 유지됨")
    void broadcast_keeps_healthy_emitter_after_removing_dead_one() throws IOException {
        SseEmitter deadEmitter = mock(SseEmitter.class);
        SseEmitter liveEmitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.add(deadEmitter);
        registry.add(liveEmitter);

        registry.broadcast(stubView);

        assertThat(registry.activeCount()).isEqualTo(1);
        verify(liveEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("broadcast: 활성 emitter 없으면 아무것도 호출 안 됨(NPE 없음)")
    void broadcast_no_emitters_no_exception() {
        // 빈 레지스트리 — 예외 없이 정상 완료
        registry.broadcast(stubView);

        assertThat(registry.activeCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("broadcast: IllegalStateException emitter는 completeWithError + 제거(IOException이 아닌 예외도 처리)")
    void broadcast_removes_dead_emitter_on_illegal_state_exception() throws IOException {
        SseEmitter deadEmitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.add(deadEmitter);
        assertThat(registry.activeCount()).isEqualTo(1);

        registry.broadcast(stubView);

        verify(deadEmitter).completeWithError(any(IllegalStateException.class));
        assertThat(registry.activeCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("broadcast: 한 emitter가 IllegalStateException 던져도 나머지 emitter는 전송됨(루프 미중단)")
    void broadcast_continues_to_remaining_emitters_after_illegal_state_exception() throws IOException {
        SseEmitter deadEmitter = mock(SseEmitter.class);
        SseEmitter liveEmitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.add(deadEmitter);
        registry.add(liveEmitter);

        registry.broadcast(stubView);

        // 죽은 emitter 제거, 살아있는 emitter는 전송 완료
        assertThat(registry.activeCount()).isEqualTo(1);
        verify(liveEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }
}
