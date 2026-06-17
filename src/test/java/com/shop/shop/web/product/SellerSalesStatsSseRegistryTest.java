package com.shop.shop.web.product;

import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.web.product.dto.SalesCell;
import com.shop.shop.web.product.dto.SellerSalesSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link SellerSalesStatsSseRegistry} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>add 원자성: emitter + 매핑 동시 등록</li>
 *   <li>remove: 마지막 emitter 제거 시 매핑도 정리</li>
 *   <li>멀티탭: 같은 email 2 emitter → 매핑 1개 공유</li>
 *   <li>connectedEmails / allConnectedVariantIds</li>
 *   <li>sendTo: 정상 emitter 전송, IOException emitter 제거</li>
 *   <li>per-seller 격리: A email 전송 시 B emitter 미영향</li>
 * </ul>
 */
class SellerSalesStatsSseRegistryTest {

    private SellerSalesStatsSseRegistry registry;
    private SellerSalesSnapshot stubSnapshot;

    private static final String EMAIL_A = "sellerA@example.com";
    private static final String EMAIL_B = "sellerB@example.com";
    private static final long VARIANT_ID_1 = 100L;
    private static final long VARIANT_ID_2 = 200L;
    private static final long PRODUCT_ID_1 = 10L;
    private static final long PRODUCT_ID_2 = 20L;

    @BeforeEach
    void setUp() {
        registry = new SellerSalesStatsSseRegistry();
        stubSnapshot = new SellerSalesSnapshot(Map.of(
                PRODUCT_ID_1, new SalesCell(5L, new BigDecimal("50000.00"))
        ));
    }

    // ============================================================
    // add / remove / connectedCount
    // ============================================================

    @Test
    @DisplayName("add 후 connectedEmails에 email 포함")
    void add_registers_email_in_connected_set() {
        SseEmitter emitter = new SseEmitter();
        List<VariantProductMapping> mappings = List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1));

        registry.add(EMAIL_A, emitter, mappings);

        assertThat(registry.connectedEmails()).contains(EMAIL_A);
        assertThat(registry.connectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove 후 마지막 emitter 제거 시 email + 매핑 정리")
    void remove_last_emitter_clears_email_and_mapping() {
        SseEmitter emitter = new SseEmitter();
        List<VariantProductMapping> mappings = List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1));

        registry.add(EMAIL_A, emitter, mappings);
        registry.remove(EMAIL_A, emitter);

        assertThat(registry.connectedEmails()).doesNotContain(EMAIL_A);
        assertThat(registry.variantToProductOf(EMAIL_A)).isEmpty();
        assertThat(registry.connectedCount()).isEqualTo(0);
    }

    // ============================================================
    // 멀티탭: 같은 email 2 emitter — 매핑 1개 공유
    // ============================================================

    @Test
    @DisplayName("멀티탭: 같은 email 2 emitter add — 매핑 1개 공유, emitter 2개 등록")
    void add_multitab_same_email_shares_mapping() {
        SseEmitter emitter1 = new SseEmitter();
        SseEmitter emitter2 = new SseEmitter();
        List<VariantProductMapping> mappings = List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1));

        registry.add(EMAIL_A, emitter1, mappings);
        // 두 번째 add: 새 매핑 제공해도 최초 매핑 유지(공유)
        registry.add(EMAIL_A, emitter2, List.of(new VariantProductMapping(VARIANT_ID_2, PRODUCT_ID_2)));

        assertThat(registry.totalEmitterCount()).isEqualTo(2);
        assertThat(registry.connectedCount()).isEqualTo(1);  // email 1개
        // 매핑은 최초 연결 것 공유
        Map<Long, Long> mapping = registry.variantToProductOf(EMAIL_A);
        assertThat(mapping).containsKey(VARIANT_ID_1);
        assertThat(mapping).doesNotContainKey(VARIANT_ID_2);
    }

    @Test
    @DisplayName("멀티탭: 한 탭 닫아도 나머지 emitter + 매핑 유지")
    void remove_one_of_multitab_keeps_remaining() {
        SseEmitter emitter1 = new SseEmitter();
        SseEmitter emitter2 = new SseEmitter();
        List<VariantProductMapping> mappings = List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1));

        registry.add(EMAIL_A, emitter1, mappings);
        registry.add(EMAIL_A, emitter2, mappings);

        registry.remove(EMAIL_A, emitter1);

        assertThat(registry.totalEmitterCount()).isEqualTo(1);
        assertThat(registry.connectedEmails()).contains(EMAIL_A);
        assertThat(registry.variantToProductOf(EMAIL_A)).containsKey(VARIANT_ID_1);
    }

    // ============================================================
    // allConnectedVariantIds: 합집합
    // ============================================================

    @Test
    @DisplayName("allConnectedVariantIds — A·B 연결 시 variantId 합집합 반환")
    void all_connected_variant_ids_returns_union() {
        registry.add(EMAIL_A, new SseEmitter(),
                List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1)));
        registry.add(EMAIL_B, new SseEmitter(),
                List.of(new VariantProductMapping(VARIANT_ID_2, PRODUCT_ID_2)));

        Set<Long> union = registry.allConnectedVariantIds();

        assertThat(union).containsExactlyInAnyOrder(VARIANT_ID_1, VARIANT_ID_2);
    }

    @Test
    @DisplayName("allConnectedVariantIds — 연결 없으면 빈 세트")
    void all_connected_variant_ids_empty_when_no_connection() {
        assertThat(registry.allConnectedVariantIds()).isEmpty();
    }

    // ============================================================
    // sendTo: 전송 + 죽은 emitter 제거
    // ============================================================

    @Test
    @DisplayName("sendTo: 정상 emitter에 데이터 전송됨")
    void send_to_delivers_snapshot_to_emitter() throws IOException {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        registry.add(EMAIL_A, mockEmitter,
                List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1)));

        registry.sendTo(EMAIL_A, stubSnapshot);

        verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("sendTo: 멀티탭 — 같은 email 2 emitter 모두 전송")
    void send_to_delivers_to_all_emitters_for_email() throws IOException {
        SseEmitter mock1 = mock(SseEmitter.class);
        SseEmitter mock2 = mock(SseEmitter.class);
        List<VariantProductMapping> mappings = List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1));

        registry.add(EMAIL_A, mock1, mappings);
        registry.add(EMAIL_A, mock2, mappings);

        registry.sendTo(EMAIL_A, stubSnapshot);

        verify(mock1).send(any(SseEmitter.SseEventBuilder.class));
        verify(mock2).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("sendTo: IOException emitter는 completeWithError + 레지스트리 제거")
    void send_to_removes_dead_emitter_on_io_exception() throws IOException {
        SseEmitter deadEmitter = mock(SseEmitter.class);
        doThrow(new IOException("connection reset")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.add(EMAIL_A, deadEmitter,
                List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1)));
        assertThat(registry.totalEmitterCount()).isEqualTo(1);

        registry.sendTo(EMAIL_A, stubSnapshot);

        verify(deadEmitter).completeWithError(any(IOException.class));
        assertThat(registry.totalEmitterCount()).isEqualTo(0);
        // 마지막 emitter 제거되었으므로 email도 정리
        assertThat(registry.connectedEmails()).doesNotContain(EMAIL_A);
    }

    // ============================================================
    // per-seller 격리
    // ============================================================

    @Test
    @DisplayName("per-seller 격리: EMAIL_A sendTo 시 EMAIL_B emitter는 미영향")
    void send_to_a_does_not_affect_b_emitter() throws IOException {
        SseEmitter mockA = mock(SseEmitter.class);
        SseEmitter mockB = mock(SseEmitter.class);

        registry.add(EMAIL_A, mockA, List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1)));
        registry.add(EMAIL_B, mockB, List.of(new VariantProductMapping(VARIANT_ID_2, PRODUCT_ID_2)));

        registry.sendTo(EMAIL_A, stubSnapshot);

        verify(mockA).send(any(SseEmitter.SseEventBuilder.class));
        // B emitter는 A 전송에서 영향받지 않음
        org.mockito.Mockito.verifyNoInteractions(mockB);
    }

    @Test
    @DisplayName("sendTo: 연결 없는 email은 예외 없이 무시")
    void send_to_unknown_email_is_noop() {
        // 예외 없이 정상 완료
        registry.sendTo("unknown@example.com", stubSnapshot);
    }
}
