package com.shop.shop.product.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Elasticsearch 상품 인덱스 + alias 멱등 부트스트랩.
 *
 * <p>애플리케이션 기동 시 {@link ProductSearchIndexNames#ALIAS} alias가 존재하지 않을 때만
 * {@code products-v1} 인덱스를 생성하고 alias를 연결한다.
 * alias가 이미 존재하면 어느 인덱스를 가리키든 무동작(멱등).
 *
 * <p><b>alias-centric 설계(060 T4 리팩터)</b>:
 * 이전 구현은 {@code products-v1} 인덱스 존재 여부로 판정했으나, T4 재색인이 alias를
 * 새 버전 인덱스로 이동한 뒤 재부팅하면 빈 {@code products-v1}을 고아(orphan)로 재생성하는
 * 문제가 있었다. alias 존재를 기준으로 변경해 이 문제를 제거한다.
 *
 * <p>ES 미가용이어도 부팅을 막지 않는다(try/catch + WARN 로깅, ADR-011).
 *
 * <p><b>빈 등록 방식</b>: {@link ProductSearchIndexConfig}에서
 * {@code @ConditionalOnBean(ElasticsearchClient.class)}와 함께 {@code @Bean}으로 등록된다.
 */
@Slf4j
@RequiredArgsConstructor
public class ProductSearchIndexBootstrap implements ApplicationRunner {

    private final ProductSearchIndexAdmin admin;

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex();
    }

    /**
     * alias-centric 멱등 부트스트랩.
     *
     * <ul>
     *   <li>alias {@code products}가 존재하면 → 무동작(어느 인덱스를 가리키든 상관없음)</li>
     *   <li>alias가 없으면 → {@code products-v1} 생성 + alias 연결</li>
     * </ul>
     *
     * <p>ES 미가용 시 예외를 삼키고 WARN 로깅 후 부팅을 계속한다.
     */
    public void ensureIndex() {
        try {
            doEnsureIndex();
        } catch (Exception e) {
            log.warn("[SearchIndex] Bootstrap failed (ES may be unavailable). Boot continues. error={}",
                    e.getMessage());
        }
    }

    private void doEnsureIndex() throws Exception {
        String initialIndex = ProductSearchIndexNames.CURRENT_INDEX;
        String aliasName = ProductSearchIndexNames.ALIAS;

        if (admin.aliasExists()) {
            log.info("[SearchIndex] Alias '{}' already exists. Bootstrap skipped (idempotent).", aliasName);
            return;
        }

        log.info("[SearchIndex] Alias '{}' not found. Creating index '{}' with Nori mapping...",
                aliasName, initialIndex);
        admin.createIndex(initialIndex);
        admin.pointAliasTo(initialIndex);
        log.info("[SearchIndex] Index '{}' + alias '{}' created successfully.", initialIndex, aliasName);
    }
}
