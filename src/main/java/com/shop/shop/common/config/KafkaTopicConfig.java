package com.shop.shop.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * shop-core Kafka 토픽 선언(프로비저닝) 설정.
 *
 * <p>역할: {@code @Externalized} 이벤트가 외부화되는 Kafka 토픽을 앱 기동 시 멱등 생성한다.
 * Spring Boot가 자동구성한 {@code KafkaAdmin}이 이 {@link NewTopic} 빈들을 감지해
 * {@code AdminClient.createTopics}(이미 존재하면 무시)로 생성한다.
 *
 * <p>운영(docker-compose.prod.yml)은 브로커 {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE=false}이므로
 * 토픽이 자동 생성되지 않는다. 이 선언이 토픽 생성의 단일 책임 지점이다(SSOT). 로컬 dev는
 * auto-create가 켜져 있어도 이 빈으로 동일하게 멱등 생성되므로 환경 간 동작이 일치한다.
 *
 * <p>토픽명은 각 이벤트의 {@code @Externalized(...)}/{@code TOPIC} 상수와 일치해야 한다.
 * 도메인 이벤트 클래스를 직접 import하면 common → 도메인 모듈 역의존(architecture-rule 위반)이
 * 발생하므로, 여기서는 문자열 상수로 미러링한다. 토픽을 추가/변경하면 양쪽을 함께 갱신할 것.
 *
 * <p>단일 브로커(KRaft) 구성이므로 replication factor=1. 파티션=1(현 처리량 기준, 추후 상향 가능).
 */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 1;
    private static final short REPLICAS = 1;

    // notification이 구독하는 공개 이벤트 토픽 6종 (docs/architecture.md 섹션 5 이벤트 계약)
    private static final String MEMBER_REGISTERED = "member-registered";
    private static final String PASSWORD_RESET_REQUESTED = "password-reset-requested";
    private static final String ORDER_COMPLETED = "order-completed";
    private static final String ORDER_CANCELLED = "order-cancelled";
    private static final String SHIPPING_STARTED = "shipping-started";
    private static final String PAYMENT_FAILED = "payment-failed";
    // Outbox 스모크 검증 전용 토픽 (notification 미구독, platform 모듈)
    private static final String SHOP_CORE_SMOKE_TEST = "shop-core-smoke-test";
    // 상품 색인 전용 토픽 (notification 미구독 — shop-core product-search-indexer 전용)
    private static final String PRODUCT_SEARCH_INDEX_CHANGED = "product-search-index-changed";
    // DLQ 토픽: 독성 이벤트 격리 (DeadLetterPublishingRecoverer suffix ".DLQ" 일치)
    private static final String PRODUCT_SEARCH_INDEX_CHANGED_DLQ = PRODUCT_SEARCH_INDEX_CHANGED + ".DLQ";

    @Bean
    public NewTopic memberRegisteredTopic() {
        return topic(MEMBER_REGISTERED);
    }

    @Bean
    public NewTopic passwordResetRequestedTopic() {
        return topic(PASSWORD_RESET_REQUESTED);
    }

    @Bean
    public NewTopic orderCompletedTopic() {
        return topic(ORDER_COMPLETED);
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return topic(ORDER_CANCELLED);
    }

    @Bean
    public NewTopic shippingStartedTopic() {
        return topic(SHIPPING_STARTED);
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return topic(PAYMENT_FAILED);
    }

    @Bean
    public NewTopic shopCoreSmokeTestTopic() {
        return topic(SHOP_CORE_SMOKE_TEST);
    }

    @Bean
    public NewTopic productSearchIndexChangedTopic() {
        return topic(PRODUCT_SEARCH_INDEX_CHANGED);
    }

    @Bean
    public NewTopic productSearchIndexChangedDlqTopic() {
        return topic(PRODUCT_SEARCH_INDEX_CHANGED_DLQ);
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
