package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import com.shop.shop.product.dto.SellerProductStatsData;
import com.shop.shop.product.dto.SellerProductSummaryView;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.web.product.dto.SalesCell;
import com.shop.shop.web.product.dto.SellerProductStatsRow;
import com.shop.shop.web.product.dto.SellerSalesSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 판매자 상품 현황 페이지 데이터 조합 컴포넌트.
 *
 * <p>product.spi({@link SellerProductFacade})와 order.spi({@link SellerSalesStatsPort})의
 * 결과를 상품 기준으로 병합한다. 조합 로직은 web 패키지에만 위치한다(product/order 모듈에 두지 않음).
 *
 * <p>IDOR 안전: variantId는 항상 소유 검증된 상품에서만 파생된다.
 * 외부 productId/variantId 입력을 신뢰하지 않는다.
 *
 * <p>{@link #mergeSalesByProduct(Map, List)} 공통 메서드를 {@link #assemble(String, Pageable)} 페이지 경로와
 * SSE 경로({@link SellerSalesStatsSseBroadcaster})가 함께 사용해 수치 발산을 방지한다(plan §1④).
 */
@Component
@RequiredArgsConstructor
public class SellerProductStatsAssembler {

    private final SellerProductFacade sellerProductFacade;
    private final SellerSalesStatsPort sellerSalesStatsPort;

    /**
     * 판매자 본인 상품의 현황 통계 행 목록(Page)을 조합한다.
     *
     * <p>절차:
     * <ol>
     *   <li>소유 상품 페이지 + 재고맵 + variantId 매핑 조회 (소유 검증 포함)</li>
     *   <li>소유 variantId 전체 → {@link SellerSalesStatsPort#aggregateByVariantIds} 호출</li>
     *   <li>{@link #mergeSalesByProduct}로 variantId→productId 매핑 기준 상품별 판매 병합</li>
     *   <li>행 DTO 리스트 구성 → Page 래핑</li>
     * </ol>
     *
     * @param actorEmail 행위자 이메일 (facade 내부에서 ownerId로 해석)
     * @param pageable   페이지 정보
     * @return 상품별 현황 통계 행 Page
     */
    public Page<SellerProductStatsRow> assemble(String actorEmail, Pageable pageable) {
        // 1. 소유 상품 데이터 조회 (소유 검증 포함)
        SellerProductStatsData statsData = sellerProductFacade.getMyProductStatsData(actorEmail, pageable);
        List<SellerProductSummaryView> products = statsData.products();

        if (products.isEmpty()) {
            return Page.empty(pageable);
        }

        // 2. 소유 variantId 전체 수집 → order SPI 호출
        List<Long> allVariantIds = statsData.variantMappings().stream()
                .map(VariantProductMapping::variantId)
                .collect(Collectors.toList());

        List<VariantSalesAggregate> salesAggregates = sellerSalesStatsPort.aggregateByVariantIds(allVariantIds);

        // 3. variantId → productId 맵
        Map<Long, Long> productIdByVariantId = statsData.variantMappings().stream()
                .collect(Collectors.toMap(VariantProductMapping::variantId, VariantProductMapping::productId));

        // 4. 상품별 판매 집계 병합 (공통 메서드 사용 — SSE 경로와 수치 일치 보장)
        Map<Long, SalesCell> salesByProduct = mergeSalesByProduct(productIdByVariantId, salesAggregates);

        // 5. 행 DTO 구성
        List<SellerProductStatsRow> rows = products.stream()
                .map(product -> {
                    long totalStock = statsData.stockByProduct().getOrDefault(product.productId(), 0L);
                    SalesCell cell = salesByProduct.getOrDefault(product.productId(), SalesCell.ZERO);
                    return new SellerProductStatsRow(
                            product.productId(),
                            product.name(),
                            product.status(),
                            totalStock,
                            cell.salesQty(),
                            cell.revenue()
                    );
                })
                .collect(Collectors.toList());

        // 6. Page 래핑 — totalElements는 facade의 SellerProductStatsData에서 정확한 값 사용
        return new PageImpl<>(rows, pageable, statsData.totalElements());
    }

    /**
     * variantId → productId 매핑과 variant별 판매 집계를 상품 기준으로 병합한다.
     *
     * <p>페이지 경로({@link #assemble})와 SSE 경로({@link SellerSalesStatsSseBroadcaster})가
     * 이 메서드를 공유해 동일한 수치를 사용한다(plan ④ 수치 발산 방지).
     *
     * <p>판매 집계가 없는 productId는 결과 맵에 포함되지 않는다(호출자가 {@link SalesCell#ZERO}로 처리).
     *
     * @param productIdByVariantId variantId → productId 역방향 맵
     * @param salesAggregates      variant별 판매 집계 리스트
     * @return productId → {@link SalesCell} 맵 (판매가 없는 상품은 키 없음)
     */
    public Map<Long, SalesCell> mergeSalesByProduct(
            Map<Long, Long> productIdByVariantId,
            List<VariantSalesAggregate> salesAggregates) {

        Map<Long, long[]> qtyByProduct = new HashMap<>();
        Map<Long, BigDecimal> revenueByProduct = new HashMap<>();

        for (VariantSalesAggregate agg : salesAggregates) {
            Long productId = productIdByVariantId.get(agg.variantId());
            if (productId == null) {
                continue;
            }
            qtyByProduct.merge(productId, new long[]{agg.salesQty()}, (a, b) -> new long[]{a[0] + b[0]});
            revenueByProduct.merge(productId, agg.revenue(), BigDecimal::add);
        }

        Map<Long, SalesCell> result = new HashMap<>();
        for (Map.Entry<Long, long[]> entry : qtyByProduct.entrySet()) {
            Long productId = entry.getKey();
            long qty = entry.getValue()[0];
            BigDecimal revenue = revenueByProduct.getOrDefault(productId, BigDecimal.ZERO);
            result.put(productId, new SalesCell(qty, revenue));
        }
        return result;
    }

    /**
     * variant 매핑과 집계로 SSE 스냅샷을 빌드한다.
     *
     * <p>SSE 컨트롤러(초기 스냅샷)와 broadcaster(tick 스냅샷) 양쪽에서 사용한다.
     *
     * @param variantMappings  소유 variantId ↔ productId 매핑
     * @param salesAggregates  variant별 판매 집계 리스트
     * @return {@link SellerSalesSnapshot} — productId 기준 판매 맵
     */
    public SellerSalesSnapshot buildSnapshot(
            List<VariantProductMapping> variantMappings,
            List<VariantSalesAggregate> salesAggregates) {

        Map<Long, Long> productIdByVariantId = variantMappings.stream()
                .collect(Collectors.toMap(VariantProductMapping::variantId, VariantProductMapping::productId));

        Map<Long, SalesCell> salesByProduct = mergeSalesByProduct(productIdByVariantId, salesAggregates);
        return new SellerSalesSnapshot(salesByProduct);
    }
}
