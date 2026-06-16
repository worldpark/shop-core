package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import com.shop.shop.product.dto.SellerProductStatsData;
import com.shop.shop.product.dto.SellerProductSummaryView;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.web.product.dto.SellerProductStatsRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
     *   <li>variantId→productId 매핑으로 판매 집계를 상품 기준으로 병합</li>
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

        // 4. 상품별 판매 집계 병합 (variantId → productId 역방향 합산)
        Map<Long, long[]> salesByProduct = new java.util.HashMap<>();
        Map<Long, BigDecimal> revenueByProduct = new java.util.HashMap<>();

        for (VariantSalesAggregate agg : salesAggregates) {
            Long productId = productIdByVariantId.get(agg.variantId());
            if (productId == null) {
                continue;
            }
            salesByProduct.merge(productId, new long[]{agg.salesQty()}, (a, b) -> new long[]{a[0] + b[0]});
            revenueByProduct.merge(productId, agg.revenue(), BigDecimal::add);
        }

        // 5. 행 DTO 구성
        List<SellerProductStatsRow> rows = products.stream()
                .map(product -> {
                    long totalStock = statsData.stockByProduct().getOrDefault(product.productId(), 0L);
                    long salesQty = salesByProduct.containsKey(product.productId())
                            ? salesByProduct.get(product.productId())[0]
                            : 0L;
                    BigDecimal revenue = revenueByProduct.getOrDefault(product.productId(), BigDecimal.ZERO);
                    return new SellerProductStatsRow(
                            product.productId(),
                            product.name(),
                            product.status(),
                            totalStock,
                            salesQty,
                            revenue
                    );
                })
                .collect(Collectors.toList());

        // 6. Page 래핑 — totalElements는 facade의 SellerProductStatsData에서 정확한 값 사용
        return new PageImpl<>(rows, pageable, statsData.totalElements());
    }
}
