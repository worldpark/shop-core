package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Elasticsearch 상품 색인 upsert 서비스.
 *
 * <p>{@link ProductSearchIndexChangedEvent}를 {@link ProductSearchDocument}로 변환한 뒤
 * ES에 {@code _id=productId}, {@code external version=occurredAt epoch millis}로 upsert한다.
 *
 * <p><b>빈 등록 방식</b>: {@link ProductSearchIndexConfig}에서
 * {@code @ConditionalOnBean(ElasticsearchClient.class)}와 함께 {@code @Bean}으로 등록된다.
 * {@code @Service}를 직접 붙이면 컴포넌트 스캔 단계(자동설정 이전)에서 조건 평가가 이루어져
 * {@code ElasticsearchClient}가 아직 없으므로 빈 생성이 스킵되는 문제가 있어
 * {@code @Configuration} 레벨 등록으로 분리하였다.
 *
 * <p>버전 충돌(순서 역전) 처리: external version 거부({@code version_conflict_engine_exception})는
 * 늦게 도착한 옛 이벤트가 최신 문서를 덮어쓰지 못한 정상 동작이므로 swallow+DEBUG 로깅한다.
 * 재시도/DLQ로 전파하지 않는다. 다른 ES 예외는 그대로 던져 컨슈머의 DefaultErrorHandler가 처리하게 한다.
 *
 * <p>T4(060) 전량 재색인이 {@link #upsert(ProductSearchIndexChangedEvent)}를 재사용한다.
 */
@Slf4j
@RequiredArgsConstructor
public class ProductSearchIndexService {

    private static final String VERSION_CONFLICT_TYPE = "version_conflict_engine_exception";

    private final ElasticsearchClient elasticsearchClient;

    /**
     * 이벤트 스냅샷을 ES에 멱등 upsert한다.
     *
     * <p>문서 {@code _id=productId}, external version={@code occurredAt} epoch millis.
     * 버전 충돌(순서 역전 거부)은 정상으로 처리(swallow). 그 외 예외는 상위로 전파.
     *
     * @param event 자족 스냅샷 색인 이벤트
     * @throws IOException ES 통신 오류
     * @throws ElasticsearchException 버전 충돌 외 ES 오류
     */
    public void upsert(ProductSearchIndexChangedEvent event) throws IOException {
        ProductSearchDocument document = ProductSearchDocument.from(event);
        long externalVersion = event.occurredAt().toEpochMilli();

        try {
            elasticsearchClient.index(req -> req
                    .index(ProductSearchIndexNames.ALIAS)
                    .id(String.valueOf(event.productId()))
                    .versionType(VersionType.External)
                    .version(externalVersion)
                    .document(document)
            );
            log.debug("[SearchIndex] upsert success: productId={}, version={}", event.productId(), externalVersion);
        } catch (ElasticsearchException e) {
            if (isVersionConflict(e)) {
                // 순서 역전 — 늦게 도착한 옛 이벤트가 최신 문서 덮어쓰기 거부됨. 정상 동작. DLQ 비유발.
                log.debug("[SearchIndex] version conflict(out-of-order) swallowed: productId={}, version={}, msg={}",
                        event.productId(), externalVersion, e.getMessage());
            } else {
                throw e;
            }
        } catch (IOException e) {
            // 저수준 REST 클라이언트가 version_conflict_engine_exception을 ResponseException(IOException)으로
            // 던지는 경우 메시지에서 타입을 확인해 버전 충돌이면 swallow한다.
            if (isVersionConflictIo(e)) {
                log.debug("[SearchIndex] version conflict(out-of-order, IOException) swallowed: productId={}, version={}, msg={}",
                        event.productId(), externalVersion, e.getMessage());
            } else {
                throw e;
            }
        }
    }

    /**
     * {@link ProductSearchIndexChangedEvent}를 {@link ProductSearchDocument}로 변환한다.
     * T4(060) 전량 재색인이 문서 변환 로직을 재사용할 때 이 메서드를 호출한다.
     *
     * @param event 자족 스냅샷 이벤트
     * @return ES 색인 문서
     */
    public ProductSearchDocument toDocument(ProductSearchIndexChangedEvent event) {
        return ProductSearchDocument.from(event);
    }

    private boolean isVersionConflict(ElasticsearchException e) {
        return e.error() != null
                && VERSION_CONFLICT_TYPE.equals(e.error().type());
    }

    /**
     * 저수준 REST 클라이언트({@code ResponseException})가 IOException으로 던진 경우
     * 메시지에 버전 충돌 타입이 포함되는지 확인한다.
     *
     * <p>ES Java API 고수준 클라이언트가 항상 {@link ElasticsearchException}으로 변환하지 않는
     * 경우(예: 특정 ES 버전과 클라이언트 조합)를 방어한다.
     */
    private boolean isVersionConflictIo(IOException e) {
        return e.getMessage() != null && e.getMessage().contains(VERSION_CONFLICT_TYPE);
    }
}
