package com.shop.shop.member.service;

import com.shop.shop.common.config.AppUrlProperties;
import com.shop.shop.common.config.RedisProperties;
import com.shop.shop.common.exception.InvalidPasswordResetTokenException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.event.PasswordResetRequestedEvent;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.PasswordResetTokenStore;
import com.shop.shop.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.temporal.ChronoUnit;

/**
 * PasswordResetService 단위 테스트 (Mockito).
 *
 * <p>검증 범위:
 * - request 존재 이메일: 토큰 저장 1회 + PasswordResetRequestedEvent 발행 1회 + 페이로드 정합
 * - request 미존재 이메일: 저장 없음 + 발행 없음 + 예외 없음 (enumeration 방지)
 * - confirm 유효 토큰: 비번 교체 + 토큰 소비 + refresh 삭제
 * - confirm 없는/만료/사용 토큰: InvalidPasswordResetTokenException 거부
 * - consume은 1회만 호출 (재사용 차단)
 * - isTokenValid: peek 호출, consume 미호출
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PasswordResetTokenStore tokenStore;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RedisProperties redisProperties;
    private AppUrlProperties appUrlProperties;
    private PasswordResetService passwordResetService;

    private static final long USER_ID = 1L;
    private static final String EMAIL = "user@example.com";
    private static final String NAME = "홍길동";
    private static final String BASE_URL = "http://localhost:8080";
    private static final Duration RESET_TTL = Duration.ofMinutes(30);

    @BeforeEach
    void setUp() {
        redisProperties = new RedisProperties(null, null);
        appUrlProperties = new AppUrlProperties();
        appUrlProperties.setBaseUrl(BASE_URL);

        passwordResetService = new PasswordResetService(
                memberRepository,
                passwordEncoder,
                tokenStore,
                refreshTokenStore,
                eventPublisher,
                redisProperties,
                appUrlProperties
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // requestReset — 존재 이메일
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("request_존재이메일_토큰저장1회_이벤트발행1회")
    void request_존재이메일_토큰저장_이벤트발행() {
        User user = buildUser(USER_ID, EMAIL, NAME);
        when(memberRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.of(user));

        passwordResetService.requestReset(EMAIL);

        // 토큰 저장 1회 — store(token, userId, ttl)
        verify(tokenStore, times(1)).store(anyString(), eq(USER_ID), eq(RESET_TTL));

        // 이벤트 발행 1회 — ArgumentCaptor로 페이로드 검증
        ArgumentCaptor<PasswordResetRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(PasswordResetRequestedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        PasswordResetRequestedEvent event = eventCaptor.getValue();
        assertThat(event.memberId()).isEqualTo(USER_ID);
        assertThat(event.memberEmail()).isEqualTo(EMAIL);
        assertThat(event.memberName()).isEqualTo(NAME);
        assertThat(event.resetUrl()).startsWith(BASE_URL + "/password-reset/confirm?token=");
        assertThat(event.resetUrl().length()).isGreaterThan(BASE_URL.length() + "/password-reset/confirm?token=".length());

        // expiresAt ≈ now + 30분 (±5초 허용)
        Instant expectedExpiresAt = Instant.now().plus(RESET_TTL);
        assertThat(event.expiresAt()).isCloseTo(expectedExpiresAt, within(5, ChronoUnit.SECONDS));

        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("request_존재이메일_resetUrl이_baseUrl로시작하고_token포함")
    void request_존재이메일_resetUrl_정합() {
        User user = buildUser(USER_ID, EMAIL, NAME);
        when(memberRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.of(user));

        // store 호출 시 토큰을 캡처
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        passwordResetService.requestReset(EMAIL);

        verify(tokenStore).store(tokenCaptor.capture(), eq(USER_ID), eq(RESET_TTL));
        String capturedToken = tokenCaptor.getValue();

        // 이벤트의 resetUrl에 토큰이 포함되어야 함
        ArgumentCaptor<PasswordResetRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(PasswordResetRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        String resetUrl = eventCaptor.getValue().resetUrl();
        assertThat(resetUrl).startsWith(BASE_URL);
        assertThat(resetUrl).contains(capturedToken);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // requestReset — 미존재 이메일 (enumeration 방지)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("request_미존재이메일_무저장_무발행_동일반환")
    void request_미존재이메일_noOp() {
        when(memberRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.empty());

        // 예외 없이 정상 반환
        passwordResetService.requestReset(EMAIL);

        verifyNoInteractions(tokenStore);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("request_탈퇴이메일_findActiveByEmail_empty로_동일경로")
    void request_탈퇴이메일_findActiveByEmpty_noOp() {
        // findActiveByEmail은 탈퇴 회원도 empty 반환 (ACTIVE 상태만 조회)
        when(memberRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.empty());

        passwordResetService.requestReset(EMAIL);

        verifyNoInteractions(tokenStore);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // confirmReset — 유효 토큰
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confirm_유효토큰_비번교체_토큰소비_refresh삭제")
    void confirm_유효토큰_비번교체() {
        String token = "validtoken";
        String newPassword = "newPass123";
        String encodedHash = "$2a$10$newhash";

        User user = buildUser(USER_ID, EMAIL, NAME);
        when(tokenStore.consume(token)).thenReturn(Optional.of(USER_ID));
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(newPassword)).thenReturn(encodedHash);

        passwordResetService.confirmReset(token, newPassword);

        verify(tokenStore, times(1)).consume(token);
        verify(passwordEncoder, times(1)).encode(newPassword);
        // user.changePassword는 도메인 메서드 — JPA dirty checking으로 반영
        // refresh 삭제는 트랜잭션 동기화 미활성 상태(단위 테스트)이므로 직접 호출
        verify(refreshTokenStore, times(1)).deleteRefresh(USER_ID);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // confirmReset — 무효/만료/사용 토큰
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confirm_없는토큰_InvalidPasswordResetTokenException_거부")
    void confirm_없는토큰_거부() {
        when(tokenStore.consume("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.confirmReset("unknown", "pass1234"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        verify(passwordEncoder, never()).encode(anyString());
        verify(memberRepository, never()).findById(anyLong());
        verify(refreshTokenStore, never()).deleteRefresh(anyLong());
    }

    @Test
    @DisplayName("confirm_consume은_1회만_호출_재사용차단단언")
    void confirm_consume_1회만호출() {
        User user = buildUser(USER_ID, EMAIL, NAME);
        when(tokenStore.consume("token")).thenReturn(Optional.of(USER_ID));
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any())).thenReturn("hash");

        passwordResetService.confirmReset("token", "pass1234");

        verify(tokenStore, times(1)).consume("token");
        verify(tokenStore, never()).peek(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // isTokenValid — 비소비 peek
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isTokenValid_유효토큰_true_peek호출_consume미호출")
    void isTokenValid_유효토큰_peek_위임() {
        when(tokenStore.peek("token")).thenReturn(Optional.of(USER_ID));

        boolean result = passwordResetService.isTokenValid("token");

        assertThat(result).isTrue();
        verify(tokenStore, times(1)).peek("token");
        verify(tokenStore, never()).consume(any());
    }

    @Test
    @DisplayName("isTokenValid_만료토큰_false_peek호출_consume미호출")
    void isTokenValid_만료토큰_false() {
        when(tokenStore.peek("expired")).thenReturn(Optional.empty());

        boolean result = passwordResetService.isTokenValid("expired");

        assertThat(result).isFalse();
        verify(tokenStore, times(1)).peek("expired");
        verify(tokenStore, never()).consume(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helper
    // ──────────────────────────────────────────────────────────────────────────

    private User buildUser(long id, String email, String name) {
        User user = User.of(email, "$2a$10$hash", name, null, Role.CONSUMER);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }
}
