package com.shop.shop.common.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 상품 검색 색인 Kafka 컨슈머 인프라 설정 (shop-core 최초 컨슈머).
 *
 * <p>notification의 {@code KafkaConsumerConfig}와 동형 패턴을 미러링한다.
 * ConsumerFactory / ConcurrentKafkaListenerContainerFactory / DefaultErrorHandler + DeadLetterPublishingRecoverer.
 *
 * <p>wire 포맷: Modulith ByteArrayJsonMessageConverter가 이벤트를 JSON byte[]로 변환해 발행한다.
 * StringDeserializer로 수신 후 {@link StringJsonMessageConverter}가 @KafkaListener 파라미터 타입으로 변환한다.
 *
 * <p>{@code shop.search.indexer.enabled=true}일 때만 활성화된다(기본값 미설정 → OFF).
 * 운영/로컬은 {@code application.yml}의 {@code SHOP_SEARCH_INDEXER_ENABLED:true}로 ON.
 * 기존 통합 테스트는 미설정이므로 이 빈이 생성되지 않아 {@code spring.kafka.bootstrap-servers}
 * 플레이스홀더 미해결로 인한 컨텍스트 로드 실패를 구조적으로 차단한다.
 * indexer 통합 테스트만 {@code shop.search.indexer.enabled=true}를 명시해 활성화한다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "shop.search.indexer.enabled", havingValue = "true")
public class SearchIndexKafkaConsumerConfig implements DisposableBean {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * DLQ 발행용 producer factory. Spring 빈으로 등록하지 않으므로(아래 recoverer 주석 참고)
     * 컨텍스트 종료 시 자동 close되지 않는다. {@link #destroy()}에서 명시적으로 정리하지 않으면
     * EmbeddedKafka 테스트 teardown 후 producer가 죽은 브로커로 무한 재연결을 시도해
     * 풀 병렬 스위트에서 fork JVM 종료를 막는다.
     */
    private DefaultKafkaProducerFactory<String, String> dlqProducerFactory;

    @Value("${shop.search.kafka.retry.max-attempts:3}")
    private long maxAttempts;

    @Value("${shop.search.kafka.retry.backoff-ms:1000}")
    private long backoffMs;

    @Value("${shop.search.kafka.dlq.suffix:.DLQ}")
    private String dlqSuffix;

    @Bean("searchIndexConsumerFactory")
    public ConsumerFactory<String, Object> searchIndexConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // StringJsonMessageConverter와 함께 사용: 값은 String으로 수신 후 컨버터가 @KafkaListener 파라미터 타입으로 변환
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class.getName());
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * DLQ 복구기: 재시도 소진 후 레코드를 {@code .DLQ} 토픽으로 라우팅한다.
     *
     * <p>컨슈머가 {@code StringDeserializer}로 값을 {@code String}으로 수신하므로
     * DLQ 발행에도 {@code StringSerializer}가 필요하다.
     * {@code KafkaTemplate} 빈을 Spring 컨텍스트에 등록하면
     * {@code KafkaAutoConfiguration}의 {@code @ConditionalOnMissingBean(types=KafkaTemplate)}이
     * 트리거되어 Modulith {@code kafkaEventExternalizer}가 사용할 공용 {@code KafkaTemplate}이
     * 생성되지 않으므로, 여기서는 <b>빈으로 등록하지 않고</b> 로컬 변수로 생성한다.
     */
    @Bean("searchIndexDeadLetterPublishingRecoverer")
    public DeadLetterPublishingRecoverer searchIndexDeadLetterPublishingRecoverer() {
        // StringSerializer 기반 ProducerFactory — Spring 빈이 아닌 로컬 인스턴스.
        // KafkaAutoConfiguration 의 @ConditionalOnMissingBean(KafkaTemplate) 를 건드리지 않는다.
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 필드로 보관해 destroy()에서 close — 컨텍스트 종료 후 producer leak(무한 재연결) 방지.
        this.dlqProducerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        KafkaTemplate<String, String> dlqTemplate = new KafkaTemplate<>(this.dlqProducerFactory);

        return new DeadLetterPublishingRecoverer(
                dlqTemplate,
                (record, ex) -> {
                    String dlqTopic = record.topic() + dlqSuffix;
                    log.warn("[SearchIndex DLQ] eventTopic={}, dlqTopic={}, reason={}",
                            record.topic(), dlqTopic, ex.getMessage());
                    int partition = record.partition() >= 0 ? record.partition() : 0;
                    return new TopicPartition(dlqTopic, partition);
                }
        );
    }

    @Bean("searchIndexKafkaErrorHandler")
    public DefaultErrorHandler searchIndexKafkaErrorHandler(
            @Qualifier("searchIndexDeadLetterPublishingRecoverer") DeadLetterPublishingRecoverer recoverer) {
        FixedBackOff backOff = new FixedBackOff(backoffMs, maxAttempts - 1);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean("searchIndexKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> searchIndexKafkaListenerContainerFactory(
            @Qualifier("searchIndexKafkaErrorHandler") DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(searchIndexConsumerFactory());
        factory.setCommonErrorHandler(errorHandler);
        // StringJsonMessageConverter: 값을 String으로 수신 후 @KafkaListener 파라미터 타입으로 역직렬화
        factory.setRecordMessageConverter(new StringJsonMessageConverter());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * 컨텍스트 종료 시 빈이 아닌 DLQ producer factory를 명시적으로 close 한다.
     * (운영에선 정상 종료 정리, 테스트에선 EmbeddedKafka teardown 후 producer leak 차단.)
     */
    @Override
    public void destroy() {
        if (dlqProducerFactory != null) {
            dlqProducerFactory.destroy();
        }
    }
}
