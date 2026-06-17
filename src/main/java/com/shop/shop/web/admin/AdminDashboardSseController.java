package com.shop.shop.web.admin;

import com.shop.shop.web.admin.dto.AdminDashboardView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;

/**
 * 관리자 대시보드 SSE 스트림 엔드포인트.
 *
 * <p>경로: {@code GET /admin/dashboard/stream}, {@code produces=text/event-stream}.
 * 별도 {@code @RestController}로 분리해 {@link AdminDashboardViewController}({@code @Controller})의
 * view name 반환 해석과 혼선을 방지한다.
 *
 * <p>인가: SecurityConfig View 체인 {@code /admin/**} → {@code hasRole("ADMIN")} 위임.
 * 컨트롤러에서 문자열 권한 검사 금지 — SecurityConfig에 위임.
 * 경로가 {@code /admin/**}(View 체인·세션 쿠키)이므로 EventSource는 세션 쿠키로 인증된다(Bearer 헤더 불요).
 * GET 요청이라 CSRF 무관.
 *
 * <p>레이어: View 계층(architecture-rule — ServiceResponse 미사용).
 */
@Slf4j
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardSseController {

    private final AdminDashboardSseRegistry registry;
    private final AdminDashboardAssembler assembler;

    @Value("${shop.admin.dashboard.sse.timeout:PT30M}")
    private Duration sseTimeout;

    /**
     * SSE 스트림 연결 핸들러.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>SseEmitter 생성(타임아웃 설정)</li>
     *   <li>초기 스냅샷 빌드 — build 실패 시 레지스트리 등록 전에 예외 전파(dead emitter 누수 방지)</li>
     *   <li>레지스트리 등록</li>
     *   <li>onCompletion/onTimeout/onError 콜백에서 레지스트리 제거(누수 방지)</li>
     *   <li>이미 빌드한 스냅샷 전송(초기 동기화)</li>
     * </ol>
     *
     * @return SseEmitter — Spring MVC가 비동기 처리
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());

        // build를 registry.add 이전에 호출 — build가 RuntimeException(DataAccessException 등)을
        // 던지면 레지스트리 등록 자체가 일어나지 않아 dead emitter 누수를 원천 차단한다.
        AdminDashboardView snapshot = assembler.build();

        registry.add(emitter);

        emitter.onCompletion(() -> registry.remove(emitter));
        emitter.onTimeout(() -> registry.remove(emitter));
        emitter.onError(e -> registry.remove(emitter));

        // 연결 직후 현재 스냅샷 1건 전송(초기 동기화)
        try {
            emitter.send(SseEmitter.event()
                    .name("stats")
                    .data(snapshot, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("[SSE] initial snapshot send failed: {}", e.getMessage());
            emitter.completeWithError(e);
        }

        return emitter;
    }
}
