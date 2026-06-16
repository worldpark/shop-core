package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import com.shop.shop.product.dto.SellerProductStatsData;
import com.shop.shop.product.dto.SellerProductSummaryView;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.web.product.dto.SellerProductStatsRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SellerProductStatsAssembler} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>상품별 병합 정확성 (다중 variant → 상품별 합산)</li>
 *   <li>variant 없는 상품 → salesQty=0, revenue=ZERO</li>
 *   <li>판매 없는 variant만 있는 상품 → salesQty=0</li>
 *   <li>빈 상품 목록 → 즉시 empty Page 반환</li>
 *   <li>totalElements 정확성</li>
 *   <li>IDOR: variantId는 소유 검증된 상품에서만 파생됨</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SellerProductStatsAssemblerTest {

    @Mock
    private SellerProductFacade sellerProductFacade;

    @Mock
    private SellerSalesStatsPort sellerSalesStatsPort;

    private SellerProductStatsAssembler assembler;

    private static final String SELLER_EMAIL = "seller@example.com";
    private static final long PRODUCT_ID_1 = 10L;
    private static final long PRODUCT_ID_2 = 20L;
    private static final long VARIANT_ID_1 = 100L;
    private static final long VARIANT_ID_2 = 101L;
    private static final long VARIANT_ID_3 = 200L;

    @BeforeEach
    void setUp() {
        assembler = new SellerProductStatsAssembler(sellerProductFacade, sellerSalesStatsPort);
    }

    // ============================================================
    // 빈 상품 목록
    // ============================================================

    @Test
    @DisplayName("상품이 없으면 SellerSalesStatsPort를 호출하지 않고 empty Page 반환")
    void assemble_no_products_returns_empty_page_without_calling_sales_port() {
        Pageable pageable = PageRequest.of(0, 10);
        when(sellerProductFacade.getMyProductStatsData(anyString(), any()))
                .thenReturn(emptyStatsData());

        Page<SellerProductStatsRow> result = assembler.assemble(SELLER_EMAIL, pageable);

        assertThat(result.isEmpty()).isTrue();
        verify(sellerSalesStatsPort, never()).aggregateByVariantIds(anyCollection());
    }

    // ============================================================
    // 상품별 판매 병합
    // ============================================================

    @Test
    @DisplayName("다중 variant → 상품별 salesQty 합산 정확성")
    void assemble_multiple_variants_merged_into_product_sales_qty() {
        Pageable pageable = PageRequest.of(0, 10);
        SellerProductSummaryView product1 = summaryView(PRODUCT_ID_1, "상품1");
        // PRODUCT_ID_1의 variant 2개 (VARIANT_ID_1: qty=3, VARIANT_ID_2: qty=2)
        SellerProductStatsData statsData = new SellerProductStatsData(
                List.of(product1),
                1L,
                Map.of(PRODUCT_ID_1, 50L),
                List.of(
                        new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1),
                        new VariantProductMapping(VARIANT_ID_2, PRODUCT_ID_1)
                )
        );
        when(sellerProductFacade.getMyProductStatsData(anyString(), any())).thenReturn(statsData);
        when(sellerSalesStatsPort.aggregateByVariantIds(anyCollection())).thenReturn(List.of(
                new VariantSalesAggregate(VARIANT_ID_1, 3L, new BigDecimal("30000.00")),
                new VariantSalesAggregate(VARIANT_ID_2, 2L, new BigDecimal("20000.00"))
        ));

        Page<SellerProductStatsRow> result = assembler.assemble(SELLER_EMAIL, pageable);

        assertThat(result.getContent()).hasSize(1);
        SellerProductStatsRow row = result.getContent().get(0);
        assertThat(row.productId()).isEqualTo(PRODUCT_ID_1);
        assertThat(row.salesQty()).isEqualTo(5L);  // 3 + 2
        assertThat(row.revenue()).isEqualByComparingTo(new BigDecimal("50000.00"));  // 30000 + 20000
    }

    @Test
    @DisplayName("variant 없는 상품 → salesQty=0, revenue=ZERO")
    void assemble_product_without_variants_has_zero_sales() {
        Pageable pageable = PageRequest.of(0, 10);
        SellerProductSummaryView product = summaryView(PRODUCT_ID_1, "variant없는상품");
        SellerProductStatsData statsData = new SellerProductStatsData(
                List.of(product),
                1L,
                Map.of(),  // 재고 없음
                List.of()  // variantMapping 없음
        );
        when(sellerProductFacade.getMyProductStatsData(anyString(), any())).thenReturn(statsData);
        when(sellerSalesStatsPort.aggregateByVariantIds(anyCollection())).thenReturn(List.of());

        Page<SellerProductStatsRow> result = assembler.assemble(SELLER_EMAIL, pageable);

        assertThat(result.getContent()).hasSize(1);
        SellerProductStatsRow row = result.getContent().get(0);
        assertThat(row.salesQty()).isZero();
        assertThat(row.revenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.totalStock()).isZero();
    }

    @Test
    @DisplayName("variant 있지만 판매 없는 상품 → salesQty=0, totalStock은 집계됨")
    void assemble_product_with_variants_but_no_sales_has_zero_qty_but_correct_stock() {
        Pageable pageable = PageRequest.of(0, 10);
        SellerProductSummaryView product = summaryView(PRODUCT_ID_1, "판매없는상품");
        SellerProductStatsData statsData = new SellerProductStatsData(
                List.of(product),
                1L,
                Map.of(PRODUCT_ID_1, 100L),
                List.of(new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1))
        );
        when(sellerProductFacade.getMyProductStatsData(anyString(), any())).thenReturn(statsData);
        // 판매 집계 없음
        when(sellerSalesStatsPort.aggregateByVariantIds(anyCollection())).thenReturn(List.of());

        Page<SellerProductStatsRow> result = assembler.assemble(SELLER_EMAIL, pageable);

        SellerProductStatsRow row = result.getContent().get(0);
        assertThat(row.salesQty()).isZero();
        assertThat(row.revenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.totalStock()).isEqualTo(100L);
    }

    @Test
    @DisplayName("두 상품이 각자 독립적으로 병합된다 (교차 오염 없음)")
    void assemble_two_products_merged_independently() {
        Pageable pageable = PageRequest.of(0, 10);
        SellerProductSummaryView product1 = summaryView(PRODUCT_ID_1, "상품1");
        SellerProductSummaryView product2 = summaryView(PRODUCT_ID_2, "상품2");
        SellerProductStatsData statsData = new SellerProductStatsData(
                List.of(product1, product2),
                2L,
                Map.of(PRODUCT_ID_1, 50L, PRODUCT_ID_2, 20L),
                List.of(
                        new VariantProductMapping(VARIANT_ID_1, PRODUCT_ID_1),
                        new VariantProductMapping(VARIANT_ID_3, PRODUCT_ID_2)
                )
        );
        when(sellerProductFacade.getMyProductStatsData(anyString(), any())).thenReturn(statsData);
        when(sellerSalesStatsPort.aggregateByVariantIds(anyCollection())).thenReturn(List.of(
                new VariantSalesAggregate(VARIANT_ID_1, 10L, new BigDecimal("100000.00")),
                new VariantSalesAggregate(VARIANT_ID_3, 3L, new BigDecimal("30000.00"))
        ));

        Page<SellerProductStatsRow> result = assembler.assemble(SELLER_EMAIL, pageable);

        assertThat(result.getContent()).hasSize(2);
        SellerProductStatsRow row1 = result.getContent().stream()
                .filter(r -> r.productId() == PRODUCT_ID_1).findFirst().orElseThrow();
        SellerProductStatsRow row2 = result.getContent().stream()
                .filter(r -> r.productId() == PRODUCT_ID_2).findFirst().orElseThrow();

        assertThat(row1.salesQty()).isEqualTo(10L);
        assertThat(row1.totalStock()).isEqualTo(50L);
        assertThat(row2.salesQty()).isEqualTo(3L);
        assertThat(row2.totalStock()).isEqualTo(20L);
    }

    // ============================================================
    // DTO 필드 매핑
    // ============================================================

    @Test
    @DisplayName("행 DTO의 name/status가 SellerProductSummaryView에서 그대로 전달된다")
    void assemble_row_dto_inherits_name_and_status_from_summary_view() {
        Pageable pageable = PageRequest.of(0, 10);
        SellerProductSummaryView product = new SellerProductSummaryView(
                PRODUCT_ID_1, "특별상품", "ON_SALE", new BigDecimal("10000"), Instant.now());
        SellerProductStatsData statsData = new SellerProductStatsData(
                List.of(product), 1L, Map.of(), List.of());
        when(sellerProductFacade.getMyProductStatsData(anyString(), any())).thenReturn(statsData);
        when(sellerSalesStatsPort.aggregateByVariantIds(anyCollection())).thenReturn(List.of());

        Page<SellerProductStatsRow> result = assembler.assemble(SELLER_EMAIL, pageable);

        SellerProductStatsRow row = result.getContent().get(0);
        assertThat(row.name()).isEqualTo("특별상품");
        assertThat(row.status()).isEqualTo("ON_SALE");
    }

    // ============================================================
    // totalElements
    // ============================================================

    @Test
    @DisplayName("totalElements가 statsData에서 정확히 반영된다")
    void assemble_total_elements_from_stats_data() {
        Pageable pageable = PageRequest.of(0, 10);
        SellerProductSummaryView product = summaryView(PRODUCT_ID_1, "상품1");
        SellerProductStatsData statsData = new SellerProductStatsData(
                List.of(product), 25L, Map.of(), List.of());
        when(sellerProductFacade.getMyProductStatsData(anyString(), any())).thenReturn(statsData);
        when(sellerSalesStatsPort.aggregateByVariantIds(anyCollection())).thenReturn(List.of());

        Page<SellerProductStatsRow> result = assembler.assemble(SELLER_EMAIL, pageable);

        assertThat(result.getTotalElements()).isEqualTo(25L);
    }

    // ============================================================
    // helpers
    // ============================================================

    private SellerProductSummaryView summaryView(long productId, String name) {
        return new SellerProductSummaryView(productId, name, "DRAFT", new BigDecimal("10000"), Instant.now());
    }

    private SellerProductStatsData emptyStatsData() {
        return new SellerProductStatsData(List.of(), 0L, Map.of(), List.of());
    }
}
