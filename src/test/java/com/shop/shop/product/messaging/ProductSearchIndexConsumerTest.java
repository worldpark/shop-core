package com.shop.shop.product.messaging;

import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import com.shop.shop.product.search.ProductSearchIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * ProductSearchIndexConsumer 단위 테스트.
 *
 * <p>컨슈머 레이어 규칙 검증:
 * <ul>
 *   <li>service.upsert로만 위임(Repository 직접 호출 없음)</li>
 *   <li>예외 비캐치 — service 예외가 그대로 전파됨</li>
 *   <li>정확히 1회 위임</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductSearchIndexConsumerTest {

    @Mock
    private ProductSearchIndexService productSearchIndexService;

    @InjectMocks
    private ProductSearchIndexConsumer consumer;

    @Test
    @DisplayName("onProductSearchIndexChanged: service.upsert를 정확히 1회 위임한다")
    void onProductSearchIndexChanged_delegatesToService_once() throws Exception {
        ProductSearchIndexChangedEvent event = sampleEvent(42L);

        consumer.onProductSearchIndexChanged(event);

        verify(productSearchIndexService, times(1)).upsert(event);
        verifyNoMoreInteractions(productSearchIndexService);
    }

    @Test
    @DisplayName("onProductSearchIndexChanged: service 예외가 그대로 전파된다(DefaultErrorHandler 위임)")
    void onProductSearchIndexChanged_propagatesServiceException() throws Exception {
        ProductSearchIndexChangedEvent event = sampleEvent(1L);
        doThrow(new RuntimeException("ES 장애 시뮬레이션")).when(productSearchIndexService).upsert(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                consumer.onProductSearchIndexChanged(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ES 장애 시뮬레이션");
    }

    private ProductSearchIndexChangedEvent sampleEvent(long productId) {
        return new ProductSearchIndexChangedEvent(
                UUID.randomUUID(),
                Instant.now(),
                productId,
                "테스트 상품",
                null,
                null,
                null,
                "ON_SALE",
                new BigDecimal("10000.00"),
                1L
        );
    }
}
