package com.shop.shop.product.service;

import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.search.ProductSearchHits;
import com.shop.shop.product.search.ProductSearchPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PublicProductService ES 읽기 경로 단위 테스트 (Mockito).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>keyword 존재 + port available → ES 경로(port 호출 + PG 재투영)</li>
 *   <li>keyword 없음 → 기존 PG 경로 (ES 미경유)</li>
 *   <li>ES 비가용 신호(empty Optional) → PG 폴백</li>
 *   <li>ObjectProvider empty → 항상 PG</li>
 *   <li>재투영 드리프트 제거 + ES 랭킹 순서 보존</li>
 *   <li>totalHits = ES totalHits (드리프트 재카운트 없음)</li>
 *   <li>keyword 길이 가드 (MAX_KEYWORD_LENGTH=100 절단)</li>
 *   <li>deep-paging 가드 (page+1)*size > 10000 → PG 폴백</li>
 *   <li>메트릭 Counter 기록 (product.search.requests tag path=es|fallback)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PublicProductServiceEsTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private ProductOptionRepository productOptionRepository;

    @Mock
    private OptionValueRepository optionValueRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductSearchPort searchPort;

    private SimpleMeterRegistry meterRegistry;

    private ObjectProvider<ProductSearchPort> searchPortProvider;

    private PublicProductService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        searchPortProvider = buildProvider(searchPort);
        service = new PublicProductService(
                productRepository, productImageRepository,
                productOptionRepository, optionValueRepository, productVariantRepository,
                searchPortProvider, meterRegistry);
    }

    // =============================================================
    // ES 경로 — keyword 있음 + port available
    // =============================================================

    @Test
    @DisplayName("keyword 있음 + ES 성공 → port 호출 + PG 재투영 결과 반환")
    void findPublicProducts_keyword_esSuccess_returnsEsRankedResult() {
        List<Long> esIds = List.of(3L, 1L, 2L);
        when(searchPort.search(eq("노트북"), isNull(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(esIds, 30L)));

        List<ProductSummaryProjection> pgProjections = List.of(
                projection(1L), projection(2L), projection(3L)); // PG 순서는 랜덤
        when(productRepository.findPublicProductSummariesByIds(eq(esIds), anyList()))
                .thenReturn(pgProjections);

        Page<ProductSummaryProjection> result = service.findPublicProducts(
                "노트북", null, PublicProductSort.LATEST, Pageable.ofSize(20));

        // ES 랭킹 순서 보존: [3, 1, 2]
        assertThat(result.getContent()).extracting(ProductSummaryProjection::productId)
                .containsExactly(3L, 1L, 2L);
        // totalHits = ES 기준
        assertThat(result.getTotalElements()).isEqualTo(30L);
    }

    @Test
    @DisplayName("keyword 있음 + ES 성공 → findPublicProductsLatest는 호출되지 않음")
    void findPublicProducts_keyword_esSuccess_doesNotCallPgListQuery() {
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(List.of(1L), 1L)));
        when(productRepository.findPublicProductSummariesByIds(anyList(), anyList()))
                .thenReturn(List.of(projection(1L)));

        service.findPublicProducts("키워드", null, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository, never()).findPublicProductsLatest(anyList(), any(), any(), any());
        verify(productRepository, never()).findPublicProductsPriceAsc(anyList(), any(), any(), any());
        verify(productRepository, never()).findPublicProductsPriceDesc(anyList(), any(), any(), any());
    }

    // =============================================================
    // keyword 없음 → 기존 PG 경로
    // =============================================================

    @Test
    @DisplayName("keyword null → ES 경로 미사용, findPublicProductsLatest 호출")
    void findPublicProducts_noKeyword_usesPgLatest() {
        when(productRepository.findPublicProductsLatest(anyList(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts(null, null, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsLatest(anyList(), isNull(), isNull(), any());
        verify(searchPort, never()).search(any(), any(), anyList(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("keyword 빈 문자열 → null 정규화 → PG 경로")
    void findPublicProducts_blankKeyword_normalizedToNull_usesPg() {
        when(productRepository.findPublicProductsLatest(anyList(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts("  ", null, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsLatest(anyList(), isNull(), isNull(), any());
        verify(searchPort, never()).search(any(), any(), anyList(), any(), anyInt(), anyInt());
    }

    // =============================================================
    // ES 비가용 신호 → PG 폴백
    // =============================================================

    @Test
    @DisplayName("ES 비가용 신호(empty Optional) → PG 폴백 (findPublicProductsLatest 호출)")
    void findPublicProducts_esUnavailable_fallbackToPg() {
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
        when(productRepository.findPublicProductsLatest(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ProductSummaryProjection> result = service.findPublicProducts(
                "키워드", null, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsLatest(anyList(), eq("키워드"), isNull(), any());
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("ES 비가용 → PRICE_ASC 폴백 시 findPublicProductsPriceAsc 호출")
    void findPublicProducts_esUnavailable_priceAscFallback() {
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
        when(productRepository.findPublicProductsPriceAsc(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts("키워드", null, PublicProductSort.PRICE_ASC, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsPriceAsc(anyList(), eq("키워드"), isNull(), any());
    }

    // =============================================================
    // ObjectProvider empty → 항상 PG
    // =============================================================

    @Test
    @DisplayName("ObjectProvider empty(ES 빈 없음) → 항상 PG 경로 (ES 미경유)")
    void findPublicProducts_emptyProvider_alwaysPg() {
        // empty provider
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSearchPort> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        PublicProductService serviceNoEs = new PublicProductService(
                productRepository, productImageRepository,
                productOptionRepository, optionValueRepository, productVariantRepository,
                emptyProvider, meterRegistry);

        when(productRepository.findPublicProductsLatest(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        serviceNoEs.findPublicProducts("키워드", null, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsLatest(anyList(), eq("키워드"), isNull(), any());
    }

    // =============================================================
    // 드리프트 제거 + 랭킹 순서 보존
    // =============================================================

    @Test
    @DisplayName("드리프트 제거 — ES에서 온 id가 PG 재투영 결과에 없으면 목록에서 제거")
    void findPublicProducts_driftRemoval_missingIdDropped() {
        List<Long> esIds = List.of(1L, 2L, 3L); // 2L은 PG에서 HIDDEN으로 변경(드리프트)
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(esIds, 3L)));

        // 2L은 status 드리프트로 제외됨 (WHERE p.status IN statuses에서 탈락)
        when(productRepository.findPublicProductSummariesByIds(eq(esIds), anyList()))
                .thenReturn(List.of(projection(1L), projection(3L)));

        Page<ProductSummaryProjection> result = service.findPublicProducts(
                "키워드", null, PublicProductSort.LATEST, Pageable.ofSize(10));

        // 2L은 제거됨, 순서 유지: [1, 3]
        assertThat(result.getContent()).extracting(ProductSummaryProjection::productId)
                .containsExactly(1L, 3L);
        // Spring Data PageImpl은 pageSize(10) > totalHits(3)이면 content.size()로 조정한다.
        // 재카운트는 하지 않고(추가 DB 쿼리 없음) Spring Data 기본 동작에 위임한다.
        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    @DisplayName("ES 랭킹 순서 보존 — PG 재투영 결과 순서와 무관하게 ES id 순서로 재조립")
    void findPublicProducts_esRankingOrderPreserved() {
        List<Long> esIds = List.of(5L, 2L, 8L, 1L);
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(esIds, 4L)));

        // PG 반환 순서는 id 역순 (ES 순서와 다름)
        when(productRepository.findPublicProductSummariesByIds(eq(esIds), anyList()))
                .thenReturn(List.of(projection(1L), projection(2L), projection(5L), projection(8L)));

        Page<ProductSummaryProjection> result = service.findPublicProducts(
                "키워드", null, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent()).extracting(ProductSummaryProjection::productId)
                .containsExactly(5L, 2L, 8L, 1L);
    }

    // =============================================================
    // keyword 길이 가드
    // =============================================================

    @Test
    @DisplayName("keyword 100자 초과 시 100자로 절단 후 ES 호출")
    void findPublicProducts_keywordTooLong_truncated() {
        String longKeyword = "a".repeat(150);
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(List.of(), 0L)));

        service.findPublicProducts(longKeyword, null, PublicProductSort.LATEST, Pageable.ofSize(10));

        // 100자로 절단된 keyword로 ES 호출
        verify(searchPort).search(eq("a".repeat(100)), any(), anyList(), any(), anyInt(), anyInt());
    }

    // =============================================================
    // deep-paging 가드
    // =============================================================

    @Test
    @DisplayName("(page+1)*size > 10000 시 ES 스킵 → PG 폴백")
    void findPublicProducts_deepPaging_fallbackToPg() {
        // page=100, size=100 → (100+1)*100 = 10100 > 10000
        when(productRepository.findPublicProductsLatest(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts("키워드", null, PublicProductSort.LATEST,
                Pageable.ofSize(100).withPage(100));

        verify(searchPort, never()).search(any(), any(), anyList(), any(), anyInt(), anyInt());
        verify(productRepository).findPublicProductsLatest(anyList(), eq("키워드"), isNull(), any());
    }

    @Test
    @DisplayName("(page+1)*size <= 10000 시 ES 경로 사용 (경계값)")
    void findPublicProducts_boundaryPaging_usesEs() {
        // page=99, size=100 → (99+1)*100 = 10000 <= 10000 → ES 경로
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(List.of(), 0L)));

        service.findPublicProducts("키워드", null, PublicProductSort.LATEST,
                Pageable.ofSize(100).withPage(99));

        verify(searchPort).search(any(), any(), anyList(), any(), anyInt(), anyInt());
    }

    // =============================================================
    // 메트릭 Counter
    // =============================================================

    @Test
    @DisplayName("ES 경로 성공 시 product.search.requests{path=es} 카운터 증가")
    void findPublicProducts_esPath_countsEsRequests() {
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(List.of(1L), 1L)));
        when(productRepository.findPublicProductSummariesByIds(anyList(), anyList()))
                .thenReturn(List.of(projection(1L)));

        service.findPublicProducts("키워드", null, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(meterRegistry.find("product.search.requests")
                .tag("path", "es").counter()).isNotNull();
        assertThat(meterRegistry.find("product.search.requests")
                .tag("path", "es").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ES 비가용 폴백 시 product.search.requests{path=fallback} 카운터 증가")
    void findPublicProducts_fallbackPath_countsFallbackRequests() {
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
        when(productRepository.findPublicProductsLatest(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts("키워드", null, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(meterRegistry.find("product.search.requests")
                .tag("path", "fallback").counter()).isNotNull();
        assertThat(meterRegistry.find("product.search.requests")
                .tag("path", "fallback").counter().count()).isEqualTo(1.0);
    }

    // =============================================================
    // ES 빈 결과 (hits empty)
    // =============================================================

    @Test
    @DisplayName("ES ids 빈 결과 → 빈 페이지 반환 (PG 재투영 생략)")
    void findPublicProducts_esEmptyIds_returnsEmptyPage() {
        when(searchPort.search(any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new ProductSearchHits(List.of(), 0L)));

        Page<ProductSummaryProjection> result = service.findPublicProducts(
                "없는검색어", null, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0L);
        verify(productRepository, never()).findPublicProductSummariesByIds(anyList(), anyList());
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private ProductSummaryProjection projection(long productId) {
        return new ProductSummaryProjection(
                productId, "상품" + productId, new BigDecimal("10000"),
                1L, "전자기기", ProductStatus.ON_SALE, 1L);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ProductSearchPort> buildProvider(ProductSearchPort port) {
        ObjectProvider<ProductSearchPort> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(port);
        return provider;
    }
}
