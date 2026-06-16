package com.shop.shop.order.service;

import com.shop.shop.order.repository.OrderItemSalesRepository;
import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SellerSalesStatsService} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>빈 variantIds → 빈 리스트 즉시 반환 (DB 조회 없음)</li>
 *   <li>null variantIds → 빈 리스트 즉시 반환</li>
 *   <li>countedStatuses 파라미터 검증 (paid/preparing/shipping/delivered 포함, cancelled/refunded/pending 제외)</li>
 *   <li>repository 위임 정확성</li>
 *   <li>집계 결과 그대로 반환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerSalesStatsServiceTest {

    @Mock
    private OrderItemSalesRepository orderItemSalesRepository;

    private SellerSalesStatsPort service;

    @BeforeEach
    void setUp() {
        service = new SellerSalesStatsService(orderItemSalesRepository);
    }

    // ============================================================
    // 빈 입력 조기 반환
    // ============================================================

    @Test
    @DisplayName("빈 variantIds → DB 조회 없이 빈 리스트 반환")
    void aggregateByVariantIds_empty_list_returns_empty_without_db_call() {
        List<VariantSalesAggregate> result = service.aggregateByVariantIds(List.of());

        assertThat(result).isEmpty();
        verify(orderItemSalesRepository, never()).aggregateSalesByVariantIds(any(), any());
    }

    @Test
    @DisplayName("null variantIds → DB 조회 없이 빈 리스트 반환")
    void aggregateByVariantIds_null_returns_empty_without_db_call() {
        List<VariantSalesAggregate> result = service.aggregateByVariantIds(null);

        assertThat(result).isEmpty();
        verify(orderItemSalesRepository, never()).aggregateSalesByVariantIds(any(), any());
    }

    // ============================================================
    // countedStatuses 검증
    // ============================================================

    @Test
    @DisplayName("countedStatuses에 paid/preparing/shipping/delivered만 포함되어야 한다")
    void aggregateByVariantIds_passes_correct_counted_statuses_to_repository() {
        List<Long> variantIds = List.of(1L, 2L);
        when(orderItemSalesRepository.aggregateSalesByVariantIds(any(), any())).thenReturn(List.of());

        service.aggregateByVariantIds(variantIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> statusCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(orderItemSalesRepository).aggregateSalesByVariantIds(any(), statusCaptor.capture());

        Collection<String> capturedStatuses = statusCaptor.getValue();
        assertThat(capturedStatuses)
                .containsExactlyInAnyOrder("paid", "preparing", "shipping", "delivered");
    }

    @Test
    @DisplayName("countedStatuses에 cancelled/refunded/pending이 포함되지 않아야 한다")
    void aggregateByVariantIds_excluded_statuses_not_in_counted_statuses() {
        List<Long> variantIds = List.of(1L);
        when(orderItemSalesRepository.aggregateSalesByVariantIds(any(), any())).thenReturn(List.of());

        service.aggregateByVariantIds(variantIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> statusCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(orderItemSalesRepository).aggregateSalesByVariantIds(any(), statusCaptor.capture());

        Collection<String> capturedStatuses = statusCaptor.getValue();
        assertThat(capturedStatuses)
                .doesNotContain("cancelled", "refunded", "pending");
    }

    // ============================================================
    // variantIds 파라미터 전달
    // ============================================================

    @Test
    @DisplayName("variantIds가 repository에 그대로 전달된다")
    void aggregateByVariantIds_passes_variant_ids_to_repository() {
        List<Long> variantIds = List.of(10L, 20L, 30L);
        when(orderItemSalesRepository.aggregateSalesByVariantIds(any(), any())).thenReturn(List.of());

        service.aggregateByVariantIds(variantIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> variantCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(orderItemSalesRepository).aggregateSalesByVariantIds(variantCaptor.capture(), any());

        assertThat(variantCaptor.getValue()).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    // ============================================================
    // 집계 결과 반환
    // ============================================================

    @Test
    @DisplayName("repository 결과를 그대로 반환한다")
    void aggregateByVariantIds_returns_repository_result_as_is() {
        List<Long> variantIds = List.of(1L, 2L);
        List<VariantSalesAggregate> expected = List.of(
                new VariantSalesAggregate(1L, 5L, new BigDecimal("50000.00")),
                new VariantSalesAggregate(2L, 3L, new BigDecimal("30000.00"))
        );
        when(orderItemSalesRepository.aggregateSalesByVariantIds(any(), any())).thenReturn(expected);

        List<VariantSalesAggregate> result = service.aggregateByVariantIds(variantIds);

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("판매가 없는 variantId는 결과에 포함되지 않는다 (repository 위임 그대로)")
    void aggregateByVariantIds_missing_variant_ids_not_in_result() {
        List<Long> variantIds = List.of(1L, 2L, 3L);
        // variant 3은 판매 없음 — repository가 해당 항목을 반환하지 않음
        List<VariantSalesAggregate> repositoryResult = List.of(
                new VariantSalesAggregate(1L, 5L, new BigDecimal("50000.00"))
        );
        when(orderItemSalesRepository.aggregateSalesByVariantIds(any(), any())).thenReturn(repositoryResult);

        List<VariantSalesAggregate> result = service.aggregateByVariantIds(variantIds);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).variantId()).isEqualTo(1L);
    }
}
