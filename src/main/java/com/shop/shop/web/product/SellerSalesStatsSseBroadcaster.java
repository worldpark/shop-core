package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.web.product.dto.SalesCell;
import com.shop.shop.web.product.dto.SellerSalesSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 판매자 판매 현황 SSE 주기 broadcaster.
 *
 * <p>{@link SellerSalesStatsSseSchedulingConfig}가 로드될 때만 {@code @Scheduled}가 발화한다.
 * {@code shop.seller.sales.sse.enabled=false}(test 포함)이면 해당 설정이 미로드되어 스케줄이 발화하지 않는다.
 *
 * <p>연결 0이면 집계 쿼리를 건너뛴다(빈 연결 최적화).
 * 연결이 있으면 전 연결 판매자의 variantId 합집합으로 {@link SellerSalesStatsPort#aggregateByVariantIds}를
 * <b>tick당 1회</b> 호출하고(N 쿼리 금지 — plan ②), 결과를 판매자별로 분배해 각 emitter에 전송한다.
 *
 * <p>레이어: Scheduler → assembler(web 조합) + order.spi — ServiceResponse 미사용(architecture-rule).
 * {@code ThreadLocal} 직접 사용 금지(가상스레드 대비 — CLAUDE.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SellerSalesStatsSseBroadcaster {

    private final SellerSalesStatsSseRegistry registry;
    private final SellerSalesStatsPort sellerSalesStatsPort;
    private final SellerProductStatsAssembler assembler;

    /**
     * 주기적으로 판매 통계를 집계해 연결된 판매자 각각에게 push한다.
     *
     * <p>기본 interval: 10초({@code PT10S}). {@code shop.seller.sales.sse.interval}로 조정 가능.
     * 연결 0이면 집계를 건너뛴다(빈 연결 최적화).
     *
     * <p>배치 처리 순서:
     * <ol>
     *   <li>연결된 전 판매자의 variantId 합집합 수집</li>
     *   <li>{@link SellerSalesStatsPort#aggregateByVariantIds(java.util.Collection)} 1회 호출 (N 쿼리 금지)</li>
     *   <li>결과를 variantId → aggregate 맵으로 조립</li>
     *   <li>판매자별 매핑으로 해당 판매자 집계만 필터 → {@link SellerProductStatsAssembler#mergeSalesByProduct} →
     *       {@link SellerSalesSnapshot} build → {@link SellerSalesStatsSseRegistry#sendTo} (per-seller 격리)</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${shop.seller.sales.sse.interval:PT10S}")
    public void broadcast() {
        Set<String> emails = registry.connectedEmails();
        if (emails.isEmpty()) {
            log.trace("[SSE-seller] no active connections — skip aggregation");
            return;
        }

        log.debug("[SSE-seller] broadcasting to {} seller(s)", emails.size());

        // 1. 전 연결 variantId 합집합 (판매자 수에 무관 — 1 배치 쿼리)
        Set<Long> unionVariantIds = registry.allConnectedVariantIds();
        if (unionVariantIds.isEmpty()) {
            log.trace("[SSE-seller] no variants in registry — skip aggregation");
            return;
        }

        // 2. 합집합 1회 집계 쿼리 (tick당 1 배치 — plan ②)
        List<VariantSalesAggregate> allAggregates = sellerSalesStatsPort.aggregateByVariantIds(unionVariantIds);

        // 3. variantId → aggregate 역맵 조립
        Map<Long, VariantSalesAggregate> aggregateByVariantId = allAggregates.stream()
                .collect(Collectors.toMap(
                        VariantSalesAggregate::variantId,
                        a -> a,
                        (a, b) -> a  // 중복 variantId는 첫 값 유지
                ));

        // 4. 판매자별 분배 — per-seller 격리 (각 판매자는 자기 variantId 데이터만 수신)
        for (String email : emails) {
            Map<Long, Long> variantToProduct = registry.variantToProductOf(email);
            if (variantToProduct.isEmpty()) {
                continue;
            }

            // 이 판매자의 variantId에 해당하는 집계만 필터링
            List<VariantSalesAggregate> sellerAggregates = new ArrayList<>();
            for (Long variantId : variantToProduct.keySet()) {
                VariantSalesAggregate agg = aggregateByVariantId.get(variantId);
                if (agg != null) {
                    sellerAggregates.add(agg);
                }
            }

            // 공통 병합 메서드로 productId 기준 집계 맵 build (assemble()과 수치 일치 보장 — plan ④)
            Map<Long, SalesCell> salesByProduct = assembler.mergeSalesByProduct(variantToProduct, sellerAggregates);
            SellerSalesSnapshot snapshot = new SellerSalesSnapshot(salesByProduct);

            registry.sendTo(email, snapshot);
        }
    }
}
