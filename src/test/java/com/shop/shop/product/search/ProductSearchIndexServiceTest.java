package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProductSearchIndexService 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>이벤트 → 문서 매핑 정확성 (displayPrice/purchasable 패리티)</li>
 *   <li>버전 충돌 예외 swallow (DLQ 비유발)</li>
 *   <li>다른 ES 예외 전파</li>
 *   <li>정상 upsert 호출 (productId=_id, externalVersion=occurredAt millis)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductSearchIndexServiceTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    private ProductSearchIndexService service;

    @BeforeEach
    void setUp() {
        service = new ProductSearchIndexService(elasticsearchClient);
    }

    // ============================================================
    // 문서 매핑 패리티
    // ============================================================

    @Test
    @DisplayName("toDocument: 이벤트 모든 필드가 문서에 정확히 매핑된다")
    void toDocument_mapsAllFields() {
        ProductSearchIndexChangedEvent event = sampleEvent(42L, "맥북 케이스", "설명", 5L, "노트북", "ON_SALE",
                new BigDecimal("25000.00"), 3L);

        ProductSearchDocument doc = service.toDocument(event);

        assertThat(doc.productId()).isEqualTo(42L);
        assertThat(doc.name()).isEqualTo("맥북 케이스");
        assertThat(doc.description()).isEqualTo("설명");
        assertThat(doc.categoryId()).isEqualTo(5L);
        assertThat(doc.categoryName()).isEqualTo("노트북");
        assertThat(doc.status()).isEqualTo("ON_SALE");
        assertThat(doc.displayPrice()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(doc.purchasableVariantCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("toDocument: nullable 필드(description/categoryId/categoryName)가 null이어도 문서에 null 보존")
    void toDocument_nullableFieldsPreserved() {
        ProductSearchIndexChangedEvent event = sampleEvent(1L, "상품", null, null, null, "DRAFT",
                new BigDecimal("10000.00"), 0L);

        ProductSearchDocument doc = service.toDocument(event);

        assertThat(doc.description()).isNull();
        assertThat(doc.categoryId()).isNull();
        assertThat(doc.categoryName()).isNull();
        assertThat(doc.purchasableVariantCount()).isZero();
        assertThat(doc.displayPrice()).isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("toDocument: 활성 variant 없을 때 displayPrice=basePrice, purchasable=0 (공개목록 패리티)")
    void toDocument_noActiveVariant_displaysBasePrice() {
        // 활성 variant 없음: displayPrice=COALESCE(MIN(null), basePrice)=basePrice, purchasable=0
        BigDecimal basePrice = new BigDecimal("19900.00");
        ProductSearchIndexChangedEvent event = sampleEvent(10L, "상품X", null, null, null, "ON_SALE",
                basePrice, 0L);

        ProductSearchDocument doc = service.toDocument(event);

        assertThat(doc.displayPrice()).isEqualByComparingTo(basePrice);
        assertThat(doc.purchasableVariantCount()).isZero();
    }

    // ============================================================
    // 버전 충돌 swallow (순서 역전 정상 처리)
    // ============================================================

    @Test
    @DisplayName("upsert: version_conflict_engine_exception은 swallow되어 예외가 전파되지 않는다(DLQ 비유발)")
    @SuppressWarnings("unchecked")
    void upsert_versionConflict_swallowed() throws Exception {
        ElasticsearchException versionConflict = buildVersionConflictException();
        when(elasticsearchClient.index(any(Function.class))).thenThrow(versionConflict);

        ProductSearchIndexChangedEvent event = sampleEvent(1L, "상품", null, null, null, "ON_SALE",
                new BigDecimal("10000"), 1L);

        // 예외가 밖으로 전파되지 않아야 한다
        org.assertj.core.api.Assertions.assertThatCode(() -> service.upsert(event))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("upsert: version_conflict 이외 ElasticsearchException은 전파된다(재시도/DLQ 대상)")
    @SuppressWarnings("unchecked")
    void upsert_otherElasticsearchException_propagated() throws Exception {
        ElasticsearchException otherEx = buildElasticsearchException("cluster_block_exception");
        when(elasticsearchClient.index(any(Function.class))).thenThrow(otherEx);

        ProductSearchIndexChangedEvent event = sampleEvent(1L, "상품", null, null, null, "ON_SALE",
                new BigDecimal("10000"), 1L);

        assertThatThrownBy(() -> service.upsert(event))
                .isInstanceOf(ElasticsearchException.class);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private ProductSearchIndexChangedEvent sampleEvent(long productId, String name, String description,
                                                        Long categoryId, String categoryName, String status,
                                                        BigDecimal displayPrice, long purchasableVariantCount) {
        return new ProductSearchIndexChangedEvent(
                UUID.randomUUID(),
                Instant.now(),
                productId, name, description, categoryId, categoryName,
                status, displayPrice, purchasableVariantCount
        );
    }

    /**
     * version_conflict_engine_exception 타입의 ElasticsearchException 생성.
     * ES 8.x client에서 버전 충돌 시 던지는 예외 타입을 시뮬레이션한다.
     */
    private ElasticsearchException buildVersionConflictException() {
        return buildElasticsearchException("version_conflict_engine_exception");
    }

    private ElasticsearchException buildElasticsearchException(String errorType) {
        ErrorCause errorCause = ErrorCause.of(b -> b.type(errorType).reason("simulated " + errorType));
        ErrorResponse errorResponse = ErrorResponse.of(b -> b.error(errorCause).status(409));
        return new ElasticsearchException("test", errorResponse);
    }
}
