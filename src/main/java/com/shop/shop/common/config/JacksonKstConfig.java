package com.shop.shop.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 시스템 전역 JSON 시각 표현을 KST(Asia/Seoul)로 통일한다.
 *
 * <p>{@link Instant}는 절대시각이라 Jackson 기본 직렬화가 항상 UTC({@code ...Z})다. 이를 KST 오프셋
 * (예: {@code 2026-06-15T14:30:00+09:00})으로 직렬화하는 {@link Module} 빈을 등록한다.
 *
 * <p><b>적용 범위</b>: Spring Boot가 {@code Module} 빈을 기본(primary) ObjectMapper에 자동 등록하므로,
 * 이 ObjectMapper를 공유하는 <b>REST 응답</b>과 <b>Spring Modulith Kafka 이벤트 외부화</b> 모두 KST로 직렬화된다
 * (시스템 전체 KST 사용 — 사용자 요구). 저장값(timestamptz/Instant)은 절대시각으로 불변.
 *
 * <p>역직렬화는 무영향: ISO-8601 오프셋(또는 Z) 문자열 모두 동일 절대 {@code Instant}로 파싱되므로,
 * notification 컨슈머(Instant 매핑)는 {@code +09:00} 표현도 같은 시각으로 정상 수신한다.
 */
@Configuration
public class JacksonKstConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Bean
    public Module kstInstantModule() {
        SimpleModule module = new SimpleModule("kst-instant");
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.atZone(KST).format(KST_FORMAT));
            }
        });
        return module;
    }
}
