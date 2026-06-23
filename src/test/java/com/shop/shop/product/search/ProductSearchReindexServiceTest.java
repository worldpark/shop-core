package com.shop.shop.product.search;

import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.ProductSearchSnapshotProjection;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.service.ProductSearchReindexService;
import com.shop.shop.product.service.ReindexStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductSearchReindexService 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>from(snapshot) 변환 패리티 (status.name(), displayPrice/purchasable 1:1)</li>
 *   <li>keyset 순회 종료 (배치들 후 빈 배치 → 종료)</li>
 *   <li>bulk 실패 시 pointAliasTo 미호출 + 상태 FAILED</li>
 *   <li>전량 성공 시 pointAliasTo 1회 + 상태 SUCCESS</li>
 *   <li>동시 startAsync → 두 번째는 IllegalStateException (409)</li>
 *   <li>ObjectProvider empty → 명확한 "비활성" IllegalStateException</li>
 *   <li>submit 실패 경로 → running 복원 + 상태 FAILED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductSearchReindexServiceTest {

    @Mock
    private ProductSearchIndexAdmin admin;

    @Mock
    private ProductRepository productRepository;

    @SuppressWarnings("unchecked")
    private ObjectProvider<ProductSearchIndexAdmin> adminProvider = mock(ObjectProvider.class);

    private ProductSearchReindexService service;

    @BeforeEach
    void setUp() {
        when(adminProvider.getIfAvailable()).thenReturn(admin);
        service = new ProductSearchReindexService(adminProvider, productRepository, 2, 0L);
    }

    // ========================================================================
    // ProductSearchDocument.from(snapshot) 패리티
    // ========================================================================

    @Test
    @DisplayName("from(snapshot): status.name(), 모든 필드 1:1 변환")
    void fromSnapshot_mapsAllFields() {
        ProductSearchSnapshotProjection snapshot = new ProductSearchSnapshotProjection(
                42L, "맥북 케이스", "좋은 상품", 5L, "노트북", ProductStatus.ON_SALE,
                new BigDecimal("25000.00"), 3L
        );

        ProductSearchDocument doc = ProductSearchDocument.from(snapshot);

        assertThat(doc.productId()).isEqualTo(42L);
        assertThat(doc.name()).isEqualTo("맥북 케이스");
        assertThat(doc.description()).isEqualTo("좋은 상품");
        assertThat(doc.categoryId()).isEqualTo(5L);
        assertThat(doc.categoryName()).isEqualTo("노트북");
        assertThat(doc.status()).isEqualTo("ON_SALE");  // enum.name()
        assertThat(doc.displayPrice()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(doc.purchasableVariantCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("from(snapshot): DRAFT status → 'DRAFT' 문자열")
    void fromSnapshot_statusDraft_name() {
        ProductSearchSnapshotProjection snapshot = new ProductSearchSnapshotProjection(
                1L, "상품", null, null, null, ProductStatus.DRAFT,
                new BigDecimal("9900"), 0L
        );

        ProductSearchDocument doc = ProductSearchDocument.from(snapshot);

        assertThat(doc.status()).isEqualTo("DRAFT");
        assertThat(doc.description()).isNull();
        assertThat(doc.categoryId()).isNull();
        assertThat(doc.purchasableVariantCount()).isZero();
    }

    // ========================================================================
    // keyset 순회 종료
    // ========================================================================

    @Test
    @DisplayName("reindex: 2배치 후 빈 배치 → 순회 종료, lastId 진행")
    void reindex_keysetTraversalTerminates() throws Exception {
        // 배치1 (id=1,2), 배치2 (id=3,4), 배치3 (빈) → 종료
        List<ProductSearchSnapshotProjection> batch1 = List.of(
                snapshot(1L, ProductStatus.ON_SALE), snapshot(2L, ProductStatus.SOLD_OUT));
        List<ProductSearchSnapshotProjection> batch2 = List.of(
                snapshot(3L, ProductStatus.DRAFT), snapshot(4L, ProductStatus.ON_SALE));
        List<ProductSearchSnapshotProjection> emptyBatch = List.of();

        when(productRepository.findSearchSnapshotsAfter(eq(0L), any(Limit.class))).thenReturn(batch1);
        when(productRepository.findSearchSnapshotsAfter(eq(2L), any(Limit.class))).thenReturn(batch2);
        when(productRepository.findSearchSnapshotsAfter(eq(4L), any(Limit.class))).thenReturn(emptyBatch);

        service.reindex();

        ReindexStatus status = service.status();
        assertThat(status.getState()).isEqualTo(ReindexStatus.State.SUCCESS);
        assertThat(status.getProcessedCount()).isEqualTo(4L);

        // bulk 2회 호출 (배치1, 배치2)
        verify(admin, times(2)).bulkIndex(anyString(), anyList());
        // swap 1회 호출
        verify(admin, times(1)).pointAliasTo(anyString());
    }

    // ========================================================================
    // bulk 실패 → alias swap 미호출 + 상태 FAILED
    // ========================================================================

    @Test
    @DisplayName("reindex: bulk 실패 → pointAliasTo 미호출 + 상태 FAILED")
    void reindex_bulkFailure_noAliasSwap_statusFailed() throws Exception {
        List<ProductSearchSnapshotProjection> batch1 = List.of(snapshot(1L, ProductStatus.ON_SALE));
        when(productRepository.findSearchSnapshotsAfter(eq(0L), any(Limit.class))).thenReturn(batch1);
        doThrow(new IllegalStateException("ES bulk failed")).when(admin).bulkIndex(anyString(), anyList());

        service.reindex();

        // alias swap 미호출
        verify(admin, never()).pointAliasTo(anyString());
        // 상태 FAILED
        assertThat(service.status().getState()).isEqualTo(ReindexStatus.State.FAILED);
        assertThat(service.status().getErrorMessage()).contains("ES bulk failed");
    }

    // ========================================================================
    // 전량 성공 → alias swap 1회 + 상태 SUCCESS
    // ========================================================================

    @Test
    @DisplayName("reindex: 전량 성공 → pointAliasTo 1회 + 상태 SUCCESS")
    void reindex_success_swapOnce_statusSuccess() throws Exception {
        List<ProductSearchSnapshotProjection> batch1 = List.of(snapshot(1L, ProductStatus.ON_SALE));
        List<ProductSearchSnapshotProjection> emptyBatch = List.of();

        when(productRepository.findSearchSnapshotsAfter(eq(0L), any(Limit.class))).thenReturn(batch1);
        when(productRepository.findSearchSnapshotsAfter(eq(1L), any(Limit.class))).thenReturn(emptyBatch);

        service.reindex();

        verify(admin, times(1)).pointAliasTo(anyString());
        assertThat(service.status().getState()).isEqualTo(ReindexStatus.State.SUCCESS);
        assertThat(service.status().getProcessedCount()).isEqualTo(1L);
        assertThat(service.status().getNewIndex()).startsWith("products-v");
    }

    // ========================================================================
    // 동시 startAsync → 두 번째 호출은 IllegalStateException
    // ========================================================================

    @Test
    @DisplayName("startAsync: 이미 RUNNING이면 IllegalStateException (409)")
    void startAsync_alreadyRunning_throws() throws Exception {
        // reflection으로 running=true로 강제 설정 — 이미 실행 중 시뮬레이션
        java.util.concurrent.atomic.AtomicBoolean runningField = getRunningField(service);
        runningField.set(true);

        // CAS 실패 → IllegalStateException
        assertThatThrownBy(() -> service.startAsync())
                .isInstanceOf(IllegalStateException.class);

        runningField.set(false);  // 정리
    }

    // ========================================================================
    // ObjectProvider empty → 명확한 "비활성" 예외
    // ========================================================================

    @Test
    @DisplayName("reindex: ES admin 부재 → '검색 엔진 비활성' IllegalStateException + 상태 FAILED")
    @SuppressWarnings("unchecked")
    void reindex_adminAbsent_throwsClearException() {
        ObjectProvider<ProductSearchIndexAdmin> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        ProductSearchReindexService serviceWithoutEs = new ProductSearchReindexService(
                emptyProvider, productRepository, 500, 0L);

        assertThatThrownBy(() -> serviceWithoutEs.reindex())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("검색 엔진 비활성");

        assertThat(serviceWithoutEs.status().getState()).isEqualTo(ReindexStatus.State.FAILED);

        serviceWithoutEs.destroy();
    }

    // ========================================================================
    // submit 실패 → running 복원
    // ========================================================================

    @Test
    @DisplayName("startAsync: executor shutdown 후 submit 실패 → running=false 복원 + FAILED")
    void startAsync_submitFails_runningReset() {
        // executor를 shutdown해 submit이 RejectedExecutionException을 던지게 함
        service.destroy();

        assertThatThrownBy(() -> service.startAsync())
                .isInstanceOf(IllegalStateException.class);

        // running이 false로 복원되어야 함 (재트리거 가능 상태)
        java.util.concurrent.atomic.AtomicBoolean runningField = getRunningField(service);
        assertThat(runningField.get()).isFalse();
        assertThat(service.status().getState()).isEqualTo(ReindexStatus.State.FAILED);
    }

    // ========================================================================
    // 헬퍼
    // ========================================================================

    private ProductSearchSnapshotProjection snapshot(long id, ProductStatus status) {
        return new ProductSearchSnapshotProjection(
                id, "상품" + id, null, null, null, status,
                new BigDecimal("10000"), 1L
        );
    }

    private java.util.concurrent.atomic.AtomicBoolean getRunningField(
            ProductSearchReindexService svc) {
        try {
            var field = ProductSearchReindexService.class.getDeclaredField("running");
            field.setAccessible(true);
            return (java.util.concurrent.atomic.AtomicBoolean) field.get(svc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
