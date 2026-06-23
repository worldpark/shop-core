package com.shop.shop.product.messaging;

import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import com.shop.shop.product.search.ProductSearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 상품 색인 변경 이벤트 Kafka 컨슈머.
 *
 * <p>로직 없음 — {@link ProductSearchIndexService}로만 위임한다(레이어 규칙).
 * Repository 직접 호출 금지. 예외를 캐치하지 않는다({@code DefaultErrorHandler} 위임).
 * 버전 충돌(순서 역전 거부)은 {@link ProductSearchIndexService#upsert}가 내부에서 swallow하므로
 * 이 컨슈머까지 전파되지 않는다.
 *
 * <p>{@code shop.search.indexer.enabled=true}일 때만 활성화된다(기본값 미설정 → OFF).
 * 운영/로컬은 {@code application.yml}의 {@code SHOP_SEARCH_INDEXER_ENABLED:true}로 ON.
 * indexer 통합 테스트만 {@code shop.search.indexer.enabled=true}를 명시해 활성화한다.
 *
 * <p>in-process 중복 수신 금지: {@code @ApplicationModuleListener}를 추가하지 않는다.
 * 색인 소비는 이 Kafka 컨슈머 1곳뿐.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "shop.search.indexer.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProductSearchIndexConsumer {

    private final ProductSearchIndexService productSearchIndexService;

    /**
     * {@code product-search-index-changed} 토픽 수신 → ES upsert 위임.
     *
     * @param event 자족 스냅샷 색인 이벤트
     * @throws Exception ES 통신 오류 (DefaultErrorHandler → DLQ 처리)
     */
    @KafkaListener(
            topics = ProductSearchIndexChangedEvent.TOPIC,
            groupId = "product-search-indexer",
            containerFactory = "searchIndexKafkaListenerContainerFactory"
    )
    public void onProductSearchIndexChanged(ProductSearchIndexChangedEvent event) throws Exception {
        log.debug("[SearchIndex] Received event: productId={}, occurredAt={}", event.productId(), event.occurredAt());
        productSearchIndexService.upsert(event);
    }
}
