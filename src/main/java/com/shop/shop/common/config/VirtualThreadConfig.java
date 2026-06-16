package com.shop.shop.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

/**
 * 가상스레드(VT) 요청 실행기 — 평가 전용, 기본 off.
 *
 * <p>활성화: {@code -Dshop.threads.virtual.enabled=true} 또는
 * {@code SHOP_THREADS_VIRTUAL_ENABLED=true} 환경변수.
 * 프로퍼티 미설정 시 빈이 생성되지 않아 Tomcat 기본 플랫폼 스레드 풀을 그대로 사용한다.</p>
 *
 * <p>교체 범위: 요청 처리 실행기(Tomcat ProtocolHandler)만 VT로 교체.
 * {@code @Async}, 스케줄러, Kafka 컨슈머는 이 빈의 영향을 받지 않는다.</p>
 *
 * <p><b>전역 {@code spring.threads.virtual.enabled} 미사용</b> — 전역 프로퍼티는
 * @Async·스케줄러를 포함한 전체 실행기를 VT로 교체해 평가 범위가 제어되지 않는다.
 * 본 빈은 측정 대상인 HTTP 요청 처리 경로만 한정적으로 교체한다.</p>
 */
@Configuration
@ConditionalOnProperty(name = "shop.threads.virtual.enabled", havingValue = "true")
public class VirtualThreadConfig {

    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadProtocolHandlerCustomizer() {
        return handler -> handler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}
