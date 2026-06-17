package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.web.product.dto.SellerSalesSnapshot;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 판매자 판매 현황 SSE 스트림 엔드포인트.
 *
 * <p>경로: {@code GET /seller/products/stats/stream}, {@code produces=text/event-stream}.
 * 별도 {@code @RestController}로 분리해 {@link SellerProductStatsViewController}({@code @Controller})의
 * view name 반환 해석과 혼선을 방지한다.
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")} 위임.
 * 경로가 {@code /seller/**}(View 체인·세션 쿠키)이므로 EventSource는 세션 쿠키로 인증된다(Bearer 헤더 불요).
 * GET 요청이라 CSRF 무관.
 *
 * <p>처리 순서:
 * <ol>
 *   <li>principal email 추출 ({@link CurrentActorResolver})</li>
 *   <li>소유 variantId 세트 조회 1쿼리 + email→ownerId 1쿼리 ({@link SellerProductFacade#getMyOwnedVariantMappings})</li>
 *   <li>초기 스냅샷 직접 빌드 ({@link SellerSalesStatsPort#aggregateByVariantIds} + assembler)</li>
 *   <li>registry.add(email, emitter, mapping) — 원자적 등록 (build 실패 시 미등록)</li>
 *   <li>onCompletion/onTimeout/onError에서 registry.remove (누수 방지)</li>
 *   <li>초기 스냅샷 전송</li>
 * </ol>
 *
 * <p>IDOR 안전: 컨트롤러는 외부 productId/variantId를 입력받지 않는다.
 * 데이터는 principal email → 소유 검증 매핑에서만 파생된다.
 *
 * <p>staleness: variantId 세트는 연결 시점 스냅샷이다.
 * 연결 유지 중 신규 등록 상품은 reconnect(새로고침) 전까지 스트림에 반영되지 않는다.
 *
 * <p>레이어: View 계층(architecture-rule — ServiceResponse 미사용).
 * {@code ThreadLocal} 직접 사용 금지(가상스레드 대비 — CLAUDE.md).
 */
@Slf4j
@RestController
@RequestMapping("/seller/products/stats")
@RequiredArgsConstructor
public class SellerSalesStatsSseController {

    private final SellerProductFacade sellerProductFacade;
    private final SellerSalesStatsPort sellerSalesStatsPort;
    private final SellerProductStatsAssembler assembler;
    private final SellerSalesStatsSseRegistry registry;
    private final CurrentActorResolver currentActorResolver;

    @Value("${shop.seller.sales.sse.timeout:PT30M}")
    private Duration sseTimeout;

    /**
     * SSE 스트림 연결 핸들러.
     *
     * <p>연결 시 소유 variantId 세트를 1회 해석해 초기 스냅샷을 직접 빌드한 후 registry에 등록한다.
     * build 실패(DataAccessException 등 RuntimeException)는 registry 등록 이전에 전파되어
     * dead emitter 누수를 원천 차단한다(046 선례).
     *
     * @param auth SecurityContext 인증 객체 (SELLER 권한 보장 — SecurityConfig 위임)
     * @return SseEmitter — Spring MVC가 비동기 처리
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String email = currentActorResolver.resolve(auth).email();
        SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());

        // 1. 소유 variantId 세트 조회 (email→ownerId 1쿼리 + 매핑 1쿼리)
        // IDOR 안전: facade 내부에서 email → ownerId 해석(외부 id 미신뢰)
        List<VariantProductMapping> variantMappings = sellerProductFacade.getMyOwnedVariantMappings(email);

        // 2. 초기 스냅샷 직접 빌드 — broadcaster union 경유 금지
        // build가 RuntimeException(DataAccessException 등)을 던지면 registry 등록 전에 전파 → dead emitter 차단
        List<Long> variantIds = variantMappings.stream()
                .map(VariantProductMapping::variantId)
                .collect(Collectors.toList());
        List<VariantSalesAggregate> salesAggregates = sellerSalesStatsPort.aggregateByVariantIds(variantIds);
        SellerSalesSnapshot snapshot = assembler.buildSnapshot(variantMappings, salesAggregates);

        // 3. registry.add — emitter + 매핑 원자적 등록
        registry.add(email, emitter, variantMappings);

        // 4. 생명주기 콜백: 종료 시 레지스트리에서 제거(마지막 emitter 제거 시 매핑도 정리)
        emitter.onCompletion(() -> registry.remove(email, emitter));
        emitter.onTimeout(() -> registry.remove(email, emitter));
        emitter.onError(e -> registry.remove(email, emitter));

        // 5. 초기 스냅샷 전송(연결 직후 현재 수치 동기화)
        try {
            emitter.send(SseEmitter.event()
                    .name("stats")
                    .data(snapshot, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("[SSE-seller] initial snapshot send failed. email={}: {}", email, e.getMessage());
            emitter.completeWithError(e);
        }

        log.debug("[SSE-seller] stream connected. email={}, variantCount={}", email, variantIds.size());
        return emitter;
    }
}
