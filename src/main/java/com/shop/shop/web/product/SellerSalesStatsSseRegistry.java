package com.shop.shop.web.product;

import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.web.product.dto.SellerSalesSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 판매자 판매 현황 SSE emitter 레지스트리 (노드 로컬, 키 = principal email).
 *
 * <p>두 가지를 email로 키잉해 보관한다:
 * <ul>
 *   <li>{@code emittersByEmail}: email별 {@link SseEmitter} 리스트 (멀티탭 지원)</li>
 *   <li>{@code variantToProductByEmail}: 연결 시점 소유 variantId ↔ productId 매핑 캐시</li>
 * </ul>
 *
 * <p>멀티탭(같은 판매자 N emitter): 동일 email 키에 emitter가 쌓이고
 * 매핑은 키별 1개(최초 연결 스냅샷) 공유 — 탭별 재해석 안 함(staleness 정책 일관).
 * 한 탭 닫힘 시 remove, 남은 emitter 있으면 매핑 유지.
 *
 * <p>노드 로컬 보관(Redis 미사용) — SSE emitter는 그 노드에 연결된 브라우저와 1:1 대응(046 근거 동일).
 *
 * <p>스레드 안전: {@link ConcurrentHashMap}과 {@link CopyOnWriteArrayList}로 보관.
 * {@code ThreadLocal} 직접 사용 금지(가상스레드 대비 — CLAUDE.md).
 */
@Slf4j
@Component
public class SellerSalesStatsSseRegistry {

    /** email → SseEmitter 리스트 (멀티탭 지원) */
    private final Map<String, List<SseEmitter>> emittersByEmail = new ConcurrentHashMap<>();

    /** email → variantId ↔ productId 매핑 캐시 (연결 시점 1회 해석, 공유) */
    private final Map<String, Map<Long, Long>> variantToProductByEmail = new ConcurrentHashMap<>();

    /**
     * emitter와 매핑을 원자적으로 등록한다.
     *
     * <p>emitter와 매핑이 함께 등록되거나 함께 미등록되도록 보장한다(부분 등록 방지).
     * 같은 email에 두 번째 이후 연결(멀티탭)은 emitter만 추가되고 매핑은 최초 것을 공유한다.
     *
     * @param email           판매자 email (레지스트리 키)
     * @param emitter         등록할 SseEmitter
     * @param variantMappings 소유 variantId ↔ productId 매핑 (최초 연결 시 캐시)
     */
    public synchronized void add(String email, SseEmitter emitter, List<VariantProductMapping> variantMappings) {
        emittersByEmail.computeIfAbsent(email, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // 매핑은 email별 1개 공유 — 최초 연결 시에만 설정(멀티탭 시 기존 매핑 유지)
        variantToProductByEmail.computeIfAbsent(email, k ->
                variantMappings.stream()
                        .collect(Collectors.toMap(
                                VariantProductMapping::variantId,
                                VariantProductMapping::productId,
                                (a, b) -> a  // 중복 variantId 있으면 첫 값 유지
                        ))
        );

        log.debug("[SSE-seller] emitter registered. email={}, emitterCount={}", email,
                emittersByEmail.get(email).size());
    }

    /**
     * emitter를 레지스트리에서 제거한다.
     *
     * <p>마지막 emitter 제거 시 해당 email의 매핑 캐시도 정리한다.
     *
     * @param email   판매자 email
     * @param emitter 제거할 SseEmitter
     */
    public synchronized void remove(String email, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByEmail.get(email);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByEmail.remove(email);
                variantToProductByEmail.remove(email);
                log.debug("[SSE-seller] last emitter removed — mapping cleared. email={}", email);
            } else {
                log.debug("[SSE-seller] emitter removed. email={}, remaining={}", email, emitters.size());
            }
        }
    }

    /**
     * 현재 연결된 판매자 email 세트를 반환한다.
     *
     * @return 연결된 email 세트 (복사본)
     */
    public Set<String> connectedEmails() {
        return new HashSet<>(emittersByEmail.keySet());
    }

    /**
     * 현재 연결된 판매자 email 수를 반환한다.
     *
     * @return 연결된 email 수
     */
    public int connectedCount() {
        return emittersByEmail.size();
    }

    /**
     * 특정 email의 variantId ↔ productId 매핑을 반환한다.
     *
     * @param email 판매자 email
     * @return variantId → productId 맵 (연결이 없으면 빈 맵)
     */
    public Map<Long, Long> variantToProductOf(String email) {
        return variantToProductByEmail.getOrDefault(email, Map.of());
    }

    /**
     * 전 연결 판매자의 variantId 합집합을 반환한다.
     *
     * <p>broadcaster가 1회 배치 쿼리를 위해 사용한다(plan §3.3 ②).
     *
     * @return 전 연결 variantId 합집합
     */
    public Set<Long> allConnectedVariantIds() {
        Set<Long> union = new HashSet<>();
        for (Map<Long, Long> mapping : variantToProductByEmail.values()) {
            union.addAll(mapping.keySet());
        }
        return union;
    }

    /**
     * 특정 email의 모든 emitter에 스냅샷을 전송한다.
     *
     * <p>{@code event=stats, data=JSON} 형식으로 전송한다.
     * 전송 중 예외가 발생한 emitter는 completeWithError 처리 후 레지스트리에서 제거한다(죽은 연결 정리).
     * 한 emitter의 실패가 나머지 전송을 중단시키지 않는다.
     *
     * @param email    판매자 email
     * @param snapshot 전송할 스냅샷 payload
     */
    public void sendTo(String email, SellerSalesSnapshot snapshot) {
        List<SseEmitter> emitters = emittersByEmail.get(email);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("stats")
                        .data(snapshot, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.debug("[SSE-seller] send failed — removing dead emitter. email={}: {}", email, e.getMessage());
                emitter.completeWithError(e);
                dead.add(emitter);
            }
        }

        if (!dead.isEmpty()) {
            synchronized (this) {
                List<SseEmitter> current = emittersByEmail.get(email);
                if (current != null) {
                    current.removeAll(dead);
                    if (current.isEmpty()) {
                        emittersByEmail.remove(email);
                        variantToProductByEmail.remove(email);
                        log.debug("[SSE-seller] all emitters dead — mapping cleared. email={}", email);
                    }
                }
            }
        }
    }

    /**
     * 전체 연결 emitter 수를 반환한다(테스트·모니터링용).
     *
     * @return 전체 연결 emitter 총 수
     */
    public int totalEmitterCount() {
        return emittersByEmail.values().stream()
                .mapToInt(Collection::size)
                .sum();
    }
}
