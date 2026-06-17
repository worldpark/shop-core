package com.shop.shop.web.admin;

import com.shop.shop.web.admin.dto.AdminDashboardView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 관리자 대시보드 SSE emitter 레지스트리.
 *
 * <p>노드 로컬 인메모리 보관 — Redis 등 공유 저장소 미사용.
 * SSE emitter는 그 노드에 연결된 admin 브라우저와 1:1 대응하므로 노드별 독립 보관이 올바르다.
 *
 * <p>스레드 안전: {@link CopyOnWriteArrayList}로 보관(읽기 경합 우위, emitter 수가 극소수인 admin 환경에 적합).
 * {@code ThreadLocal} 직접 사용 금지(가상스레드 대비 — CLAUDE.md).
 */
@Slf4j
@Component
public class AdminDashboardSseRegistry {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * emitter를 레지스트리에 추가한다.
     *
     * @param emitter 등록할 SseEmitter
     */
    public void add(SseEmitter emitter) {
        emitters.add(emitter);
        log.debug("[SSE] emitter registered. active={}", emitters.size());
    }

    /**
     * emitter를 레지스트리에서 제거한다.
     *
     * @param emitter 제거할 SseEmitter
     */
    public void remove(SseEmitter emitter) {
        emitters.remove(emitter);
        log.debug("[SSE] emitter removed. active={}", emitters.size());
    }

    /**
     * 현재 활성 emitter 수를 반환한다.
     *
     * @return 활성 emitter 수
     */
    public int activeCount() {
        return emitters.size();
    }

    /**
     * 모든 활성 emitter에 대시보드 뷰 스냅샷을 전송한다.
     *
     * <p>{@code event=stats, data=JSON} 형식으로 전송한다.
     * 전송 중 예외({@link IOException} 또는 {@link IllegalStateException} 등)가 발생한 emitter는
     * completeWithError 처리 후 레지스트리에서 제거한다(죽은 연결 정리).
     * 한 emitter의 실패가 루프를 중단시키지 않으며, 나머지 emitter는 계속 갱신된다.
     *
     * @param view 전송할 대시보드 뷰 모델
     */
    public void broadcast(AdminDashboardView view) {
        List<SseEmitter> dead = new java.util.ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("stats")
                        .data(view, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.debug("[SSE] broadcast failed — removing dead emitter: {}", e.getMessage());
                emitter.completeWithError(e);
                dead.add(emitter);
            }
        }

        emitters.removeAll(dead);
    }
}
