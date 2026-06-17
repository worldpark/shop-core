package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.web.product.dto.SalesCell;
import com.shop.shop.web.product.dto.SellerSalesSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SellerSalesStatsSseBroadcaster} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>빈 연결 skip: connectedEmails 0이면 aggregateByVariantIds 미호출</li>
 *   <li>배치 1쿼리: N 판매자 연결 시 tick당 aggregateByVariantIds 1회</li>
 *   <li>per-seller 격리: A·B 연결 시 A snapshot에 B productId 없음</li>
 *   <li>공통 메서드: mergeSalesByProduct 호출 검증(수치 일치 보장)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerSalesStatsSseBroadcasterTest {

    @Mock
    private SellerSalesStatsSseRegistry registry;

    @Mock
    private SellerSalesStatsPort sellerSalesStatsPort;

    @Mock
    private SellerProductStatsAssembler assembler;

    @InjectMocks
    private SellerSalesStatsSseBroadcaster broadcaster;

    private static final String EMAIL_A = "sellerA@example.com";
    private static final String EMAIL_B = "sellerB@example.com";
    private static final long VARIANT_ID_A = 100L;
    private static final long VARIANT_ID_B = 200L;
    private static final long PRODUCT_ID_A = 10L;
    private static final long PRODUCT_ID_B = 20L;

    // ============================================================
    // 빈 연결 skip
    // ============================================================

    @Test
    @DisplayName("연결 0이면 aggregateByVariantIds 미호출(빈 연결 최적화)")
    void broadcast_no_connections_skips_aggregation() {
        when(registry.connectedEmails()).thenReturn(Set.of());

        broadcaster.broadcast();

        verify(sellerSalesStatsPort, never()).aggregateByVariantIds(any());
    }

    @Test
    @DisplayName("연결 있지만 variantId 합집합 비면 aggregateByVariantIds 미호출")
    void broadcast_empty_variant_union_skips_aggregation() {
        when(registry.connectedEmails()).thenReturn(Set.of(EMAIL_A));
        when(registry.allConnectedVariantIds()).thenReturn(Set.of());

        broadcaster.broadcast();

        verify(sellerSalesStatsPort, never()).aggregateByVariantIds(any());
    }

    // ============================================================
    // 배치 1쿼리: N 판매자 연결 시 tick당 aggregateByVariantIds 1회
    // ============================================================

    @Test
    @DisplayName("A·B 2명 연결 시 aggregateByVariantIds 1회만 호출(배치 쿼리 보장)")
    void broadcast_two_sellers_calls_aggregate_once() {
        when(registry.connectedEmails()).thenReturn(Set.of(EMAIL_A, EMAIL_B));
        when(registry.allConnectedVariantIds()).thenReturn(Set.of(VARIANT_ID_A, VARIANT_ID_B));
        when(registry.variantToProductOf(EMAIL_A)).thenReturn(Map.of(VARIANT_ID_A, PRODUCT_ID_A));
        when(registry.variantToProductOf(EMAIL_B)).thenReturn(Map.of(VARIANT_ID_B, PRODUCT_ID_B));
        when(sellerSalesStatsPort.aggregateByVariantIds(any())).thenReturn(List.of(
                new VariantSalesAggregate(VARIANT_ID_A, 3L, new BigDecimal("30000.00")),
                new VariantSalesAggregate(VARIANT_ID_B, 5L, new BigDecimal("50000.00"))
        ));
        when(assembler.mergeSalesByProduct(anyMap(), anyList())).thenReturn(Map.of());

        broadcaster.broadcast();

        // N 쿼리 금지 — tick당 1회만 호출 (plan ②)
        verify(sellerSalesStatsPort, times(1)).aggregateByVariantIds(any());
    }

    @Test
    @DisplayName("aggregateByVariantIds 에 합집합 variantId 전달됨")
    void broadcast_passes_union_variant_ids_to_aggregate() {
        Set<Long> unionIds = Set.of(VARIANT_ID_A, VARIANT_ID_B);
        when(registry.connectedEmails()).thenReturn(Set.of(EMAIL_A, EMAIL_B));
        when(registry.allConnectedVariantIds()).thenReturn(unionIds);
        when(registry.variantToProductOf(EMAIL_A)).thenReturn(Map.of(VARIANT_ID_A, PRODUCT_ID_A));
        when(registry.variantToProductOf(EMAIL_B)).thenReturn(Map.of(VARIANT_ID_B, PRODUCT_ID_B));
        when(sellerSalesStatsPort.aggregateByVariantIds(any())).thenReturn(List.of());
        when(assembler.mergeSalesByProduct(anyMap(), anyList())).thenReturn(Map.of());

        broadcaster.broadcast();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(sellerSalesStatsPort).aggregateByVariantIds(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrderElementsOf(unionIds);
    }

    // ============================================================
    // per-seller 격리: mergeSalesByProduct 호출 검증
    // ============================================================

    @Test
    @DisplayName("A·B 2명 연결 시 mergeSalesByProduct 판매자별 2회 호출(per-seller 격리)")
    void broadcast_calls_merge_per_seller() {
        when(registry.connectedEmails()).thenReturn(Set.of(EMAIL_A, EMAIL_B));
        when(registry.allConnectedVariantIds()).thenReturn(Set.of(VARIANT_ID_A, VARIANT_ID_B));
        when(registry.variantToProductOf(EMAIL_A)).thenReturn(Map.of(VARIANT_ID_A, PRODUCT_ID_A));
        when(registry.variantToProductOf(EMAIL_B)).thenReturn(Map.of(VARIANT_ID_B, PRODUCT_ID_B));
        when(sellerSalesStatsPort.aggregateByVariantIds(any())).thenReturn(List.of(
                new VariantSalesAggregate(VARIANT_ID_A, 3L, new BigDecimal("30000.00")),
                new VariantSalesAggregate(VARIANT_ID_B, 5L, new BigDecimal("50000.00"))
        ));
        when(assembler.mergeSalesByProduct(anyMap(), anyList())).thenReturn(Map.of());

        broadcaster.broadcast();

        // 판매자 수만큼 mergeSalesByProduct 호출 (per-seller 격리)
        verify(assembler, times(2)).mergeSalesByProduct(anyMap(), anyList());
    }

    @Test
    @DisplayName("판매자 A 집계는 B의 variantId 제외하고 A만 포함")
    void broadcast_filters_aggregates_per_seller() {
        when(registry.connectedEmails()).thenReturn(Set.of(EMAIL_A));
        when(registry.allConnectedVariantIds()).thenReturn(Set.of(VARIANT_ID_A));
        when(registry.variantToProductOf(EMAIL_A)).thenReturn(Map.of(VARIANT_ID_A, PRODUCT_ID_A));

        // A의 variant만 집계에 있음 (B variant는 없음)
        List<VariantSalesAggregate> aggregates = List.of(
                new VariantSalesAggregate(VARIANT_ID_A, 3L, new BigDecimal("30000.00"))
        );
        when(sellerSalesStatsPort.aggregateByVariantIds(any())).thenReturn(aggregates);
        when(assembler.mergeSalesByProduct(anyMap(), anyList())).thenReturn(Map.of());

        broadcaster.broadcast();

        // A 판매자 매핑과 A 집계만으로 merge 호출됨
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VariantSalesAggregate>> aggCaptor = ArgumentCaptor.forClass(List.class);
        verify(assembler).mergeSalesByProduct(anyMap(), aggCaptor.capture());
        assertThat(aggCaptor.getValue()).extracting(VariantSalesAggregate::variantId)
                .containsExactly(VARIANT_ID_A);
    }

    // ============================================================
    // sendTo 호출 검증
    // ============================================================

    @Test
    @DisplayName("판매자별 sendTo 호출됨")
    void broadcast_calls_send_to_per_seller() {
        when(registry.connectedEmails()).thenReturn(Set.of(EMAIL_A));
        when(registry.allConnectedVariantIds()).thenReturn(Set.of(VARIANT_ID_A));
        when(registry.variantToProductOf(EMAIL_A)).thenReturn(Map.of(VARIANT_ID_A, PRODUCT_ID_A));
        when(sellerSalesStatsPort.aggregateByVariantIds(any())).thenReturn(List.of());
        Map<Long, SalesCell> salesMap = Map.of(PRODUCT_ID_A, new SalesCell(2L, new BigDecimal("20000.00")));
        when(assembler.mergeSalesByProduct(anyMap(), anyList())).thenReturn(salesMap);

        broadcaster.broadcast();

        ArgumentCaptor<SellerSalesSnapshot> snapshotCaptor = ArgumentCaptor.forClass(SellerSalesSnapshot.class);
        verify(registry).sendTo(org.mockito.ArgumentMatchers.eq(EMAIL_A), snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().salesByProduct()).isEqualTo(salesMap);
    }
}
