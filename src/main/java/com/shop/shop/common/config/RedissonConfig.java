package com.shop.shop.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 분산락 클라이언트 설정.
 *
 * <p><b>plain {@code org.redisson:redisson} 단독 사용 — starter 금지</b><br>
 * {@code redisson-spring-boot-starter}는 {@code RedisConnectionFactory}를 Redisson 기반으로 교체해
 * 기존 Lettuce {@code StringRedisTemplate}(RefreshToken·PasswordReset) 동작과
 * "지연 연결 덕에 브로커 없이 테스트 컨텍스트 로드 통과" 특성(C6)을 파괴한다.
 * 따라서 plain 의존만 추가하고 {@link RedissonClient} 빈을 이 설정에서 별도 등록한다.
 *
 * <p><b>DB index 0 공유</b><br>
 * 기존 Lettuce와 동일 Redis 인스턴스·DB index 0을 사용한다({@code spring.data.redis.*}).
 * 락 키는 {@code shopcore:lock:} prefix로 keyspace를 구분하므로 충돌이 없다.
 *
 * <p><b>Lettuce 무영향</b><br>
 * 기존 {@code RedisConfig}(@EnableConfigurationProperties)·Lettuce 연결팩토리·
 * {@code StringRedisTemplate}·{@code RedisAutoConfiguration}은 이 설정과 완전히 독립이다.
 *
 * <p><b>watchdog 사용 — leaseTime 미지정</b><br>
 * {@code RedissonSchedulerLeaderGuard}는 {@code tryLock(0, SECONDS)}(leaseTime 미지정)으로
 * Redisson watchdog 자동 갱신에 맡긴다. 고정 leaseTime은 작업 overrun 시 실행 중 락 만료 → 중복 실행
 * 재오픈이므로 금지(Task 035 기술제약 3).
 *
 * <p><b>무브로커 컨텍스트 로드 보존</b><br>
 * Redisson 단일 서버 Config는 빈 생성 시 즉시 연결을 시도하지 않는다.
 * 테스트 컨텍스트(브로커 없음)에서도 풀컨텍스트 로드가 통과한다.
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * Redisson 클라이언트 빈.
     *
     * <p>{@code destroyMethod = "shutdown"}: Spring 컨텍스트 종료 시 Redisson 연결 풀과 watchdog 스레드를
     * 정상 종료한다(빈 소멸 시 자동 호출).
     *
     * <p>단일 서버 모드(single server): 로컬/개발/운영 단일 Redis 인스턴스 전제.
     * 클러스터/센티넬 전환이 필요하면 {@code Config.useClusterServers()} 등으로 교체한다.
     *
     * <p>DB index: {@code spring.data.redis.database}를 따른다(기본 0).
     * Lettuce와 동일 DB를 공유하나 락 키 prefix({@code shopcore:lock:})로 keyspace 격리.
     *
     * <p><b>lazyInitialization = true</b>: 빈 생성 시점에 Redis 연결을 시도하지 않는다.
     * 첫 락 획득 시점에 연결을 수립한다. 이로써 브로커 없이도 풀컨텍스트 로드가 통과한다(C6 보존).
     * Lettuce의 "지연 연결" 특성과 동등한 동작을 Redisson에서도 달성하는 핵심 설정.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setDatabase(redisDatabase);
        // 빈 생성 시 즉시 연결 시도를 방지 → 브로커 없이 테스트 컨텍스트 로드 통과(C6 보존)
        config.setLazyInitialization(true);
        return Redisson.create(config);
    }
}
