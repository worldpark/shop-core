package com.shop.shop.security;

import java.time.Duration;
import java.util.Optional;

/**
 * 비밀번호 재설정 토큰 저장 포트 인터페이스.
 *
 * <p>운영 구현: {@link RedisPasswordResetTokenStore} (StringRedisTemplate, SHA-256 키).
 * 테스트 구현: {@code security/support/FakePasswordResetTokenStore} (인메모리 Map, @Import 전용).
 *
 * <p>토큰 원문은 저장하지 않는다. SHA-256(token)만 키로 사용(RefreshTokenStore 선례).
 * namespace: {@code shopcore:auth:reset:}.
 */
public interface PasswordResetTokenStore {

    /**
     * 비밀번호 재설정 토큰을 저장한다.
     * 내부적으로 SHA-256(token)을 키로, userId를 값으로 저장.
     * 원문 token은 저장하지 않는다.
     *
     * @param token  재설정 토큰 원문 (내부에서 SHA-256 처리)
     * @param userId 회원 식별자
     * @param ttl    키 만료 시간
     */
    void store(String token, long userId, Duration ttl);

    /**
     * 토큰의 유효성을 비소비(non-consuming) 방식으로 확인한다.
     * GET confirm 화면에서 폼 표시 여부 결정에 사용. 키를 삭제하지 않는다.
     *
     * @param token 재설정 토큰 원문
     * @return 유효한 경우 userId, 만료/미존재이면 empty
     */
    Optional<Long> peek(String token);

    /**
     * 토큰을 원자적으로 소비(consuming)한다.
     * POST confirm 성공 시 1회용 삭제. Redis GETDEL 원자 연산으로 동시 confirm 경합 시 1회만 성공 보장.
     *
     * @param token 재설정 토큰 원문
     * @return 소비된 userId, 만료/미존재/이미사용이면 empty
     */
    Optional<Long> consume(String token);
}
