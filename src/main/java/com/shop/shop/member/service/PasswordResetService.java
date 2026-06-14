package com.shop.shop.member.service;

import com.shop.shop.common.config.AppUrlProperties;
import com.shop.shop.common.config.RedisProperties;
import com.shop.shop.common.exception.InvalidPasswordResetTokenException;
import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.event.PasswordResetRequestedEvent;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.PasswordResetTokenStore;
import com.shop.shop.security.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 비밀번호 재설정 도메인 서비스.
 * 요청(requestReset) / 확정(confirmReset) / 유효성 확인(isTokenValid) 담당.
 *
 * <p>레이어: PasswordResetServiceResponse(REST) / PasswordResetFacadeImpl(View) → PasswordResetService → MemberRepository
 *
 * <p>토큰 발급: SecureRandom 32바이트 → HexFormat 인코딩(64자 hex, URL-safe).
 * 토큰 저장: PasswordResetTokenStore.store(token, userId, ttl) — SHA-256(token)만 Redis 저장, 원문 미저장.
 * refresh 무효화: AccountService.invalidateRefresh 패턴 계승 (afterCommit).
 *
 * <p>enumeration 방지: requestReset은 이메일 존재 여부와 무관하게 정상 반환.
 * 토큰 원문·resetUrl은 로그에 기록하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetTokenStore tokenStore;
    private final RefreshTokenStore refreshTokenStore;
    private final ApplicationEventPublisher eventPublisher;
    private final RedisProperties redisProperties;
    private final AppUrlProperties appUrlProperties;

    /**
     * 비밀번호 재설정 요청.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>findActiveByEmail(email.trim()) — 없으면 즉시 return(no-op, enumeration 방지).</li>
     *   <li>존재 시: 랜덤 토큰 생성(SecureRandom 32바이트 hex) → tokenStore.store(token, userId, ttl).</li>
     *   <li>resetUrl 조립 → PasswordResetRequestedEvent 발행(Transactional Outbox).</li>
     * </ol>
     *
     * <p>로그: userId/expiresAt만. 토큰·resetUrl 로그 절대 금지.
     *
     * @param email 재설정 요청 이메일 (trim 처리)
     */
    @Transactional
    public void requestReset(String email) {
        String normalizedEmail = email.trim();

        // 미존재/탈퇴 → no-op (enumeration 방지 — 동일 응답 보장)
        User user = memberRepository.findActiveByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            return;
        }

        Duration ttl = redisProperties.auth().resetTtl();
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(ttl);

        tokenStore.store(token, user.getId(), ttl);

        String resetUrl = appUrlProperties.getBaseUrl() + "/password-reset/confirm?token=" + token;

        eventPublisher.publishEvent(new PasswordResetRequestedEvent(
                UUID.randomUUID(),
                Instant.now(),
                user.getId(),
                user.getEmail(),
                user.getName(),
                resetUrl,
                expiresAt
        ));

        log.info("비밀번호 재설정 요청 처리: userId={}, expiresAt={}", user.getId(), expiresAt);
    }

    /**
     * 비밀번호 재설정 확정.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>tokenStore.consume(token) 원자 소비 — 없음/만료/사용됨 → {@link InvalidPasswordResetTokenException}(400).</li>
     *   <li>findById(userId) — 토큰 유효하나 회원 삭제된 극단 케이스 → {@link MemberNotFoundException}(404).</li>
     *   <li>새 비밀번호 BCrypt 인코딩 → user.changePassword(hash) (JPA dirty checking).</li>
     *   <li>afterCommit: refreshTokenStore.deleteRefresh(userId) (기존 세션 무효화).</li>
     * </ol>
     *
     * <p>로그: userId만. 비밀번호 원문/해시 로그 절대 금지.
     *
     * @param token       재설정 토큰 원문
     * @param newPassword 새 비밀번호 원문
     * @throws InvalidPasswordResetTokenException 토큰 무효/만료/사용됨 (400)
     * @throws MemberNotFoundException            토큰 유효하나 회원 삭제된 극단 케이스 (404)
     */
    @Transactional
    public void confirmReset(String token, String newPassword) {
        // 1. 원자 소비 (재사용 차단 — GETDEL)
        long userId = tokenStore.consume(token)
                .orElseThrow(InvalidPasswordResetTokenException::new);

        // 2. 회원 조회 (극단 케이스: 토큰 유효하나 그 사이 회원 삭제)
        User user = memberRepository.findById(userId)
                .orElseThrow(() -> new MemberNotFoundException(userId));

        // 3. 비밀번호 변경
        user.changePassword(passwordEncoder.encode(newPassword));

        log.info("비밀번호 재설정 완료: userId={}", userId);

        // 4. refresh 무효화 (afterCommit — AccountService 선례 계승)
        invalidateRefresh(userId);
    }

    /**
     * 토큰 유효성 확인 (비소비).
     * GET confirm 화면에서 폼 표시 여부 결정에 사용.
     * consume을 호출하지 않으므로 새로고침으로 토큰이 죽는 사고를 막는다.
     *
     * @param token 재설정 토큰 원문
     * @return 유효하면 true, 만료/미존재이면 false
     */
    @Transactional(readOnly = true)
    public boolean isTokenValid(String token) {
        return tokenStore.peek(token).isPresent();
    }

    /**
     * 랜덤 토큰 생성.
     * SecureRandom 32바이트 → HexFormat(64자 hex, URL-safe).
     */
    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * refresh 무효화 이중 분기 헬퍼 (AccountService.invalidateRefresh 패턴 계승).
     *
     * <p>Redis는 비트랜잭셔널(롤백 불가)이므로 트랜잭션 내 호출 시 DB 롤백 후 refresh만 삭제되는
     * 불일치가 발생할 수 있다. {@link TransactionSynchronization#afterCommit()}으로 커밋 후 호출한다.
     * 비트랜잭션 컨텍스트(일부 테스트)에서는 직접 호출한다.
     *
     * @param userId 무효화할 회원 ID
     */
    private void invalidateRefresh(long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshTokenStore.deleteRefresh(userId);
                    log.debug("refresh 무효화 완료(afterCommit): userId={}", userId);
                }
            });
        } else {
            refreshTokenStore.deleteRefresh(userId);
            log.debug("refresh 무효화 완료(직접): userId={}", userId);
        }
    }
}
