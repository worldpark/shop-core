package com.shop.shop.member.service;

import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * MemberService.recordLoginByEmail 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>ACTIVE 회원 존재 시 user.recordLogin 호출(last_login_at 갱신)</li>
 *   <li>미존재 회원: 조용히 무시 (예외 없음)</li>
 *   <li>WITHDRAWN 회원(findActiveByEmail empty): 조용히 무시 (예외 없음)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceLoginRecordTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordEncoder passwordEncoder;
    private MemberService memberService;

    private static final String EMAIL = "login-record@example.com";

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        memberService = new MemberService(memberRepository, passwordEncoder, refreshTokenStore, eventPublisher);
    }

    @Test
    @DisplayName("recordLoginByEmail — ACTIVE 회원 존재 시 lastLoginAt 갱신")
    void recordLoginByEmail_active_user_updates_lastLoginAt() {
        // given
        User user = User.of(EMAIL, "hashed_pw", "테스터", null, Role.CONSUMER);
        assertThat(user.getLastLoginAt()).isNull(); // 초기 null

        when(memberRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.of(user));

        Instant before = Instant.now();

        // when
        memberService.recordLoginByEmail(EMAIL);

        // then
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(user.getLastLoginAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("recordLoginByEmail — 미존재 이메일이면 예외 없이 무시")
    void recordLoginByEmail_unknown_email_silently_ignored() {
        // given
        when(memberRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.empty());

        // when / then — 예외 없이 정상 종료
        memberService.recordLoginByEmail(EMAIL);

        verify(memberRepository).findActiveByEmail(EMAIL);
    }

    @Test
    @DisplayName("recordLoginByEmail — WITHDRAWN 회원(findActiveByEmail empty)이면 예외 없이 무시")
    void recordLoginByEmail_withdrawn_user_silently_ignored() {
        // given — findActiveByEmail은 WITHDRAWN를 empty로 반환 (JPQL where status=ACTIVE)
        when(memberRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.empty());

        // when / then — 예외 없이 정상 종료
        memberService.recordLoginByEmail(EMAIL);

        verify(memberRepository).findActiveByEmail(EMAIL);
        // recordLogin은 호출되지 않는다 (ifPresent가 실행되지 않음)
    }
}
