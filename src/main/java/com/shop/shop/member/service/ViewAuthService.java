package com.shop.shop.member.service;

import com.shop.shop.common.exception.InvalidTokenException;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.spi.ViewAuthFacade;
import com.shop.shop.security.AuthTokenIssuer;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.RefreshTokenStore;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link ViewAuthFacade} 구현체 — View(브라우저) 인증 application 서비스.
 *
 * <p>배치: {@code member/service} — member 도메인 의존(authenticate·recordLoginByEmail)이 있으므로
 * member 모듈에 둔다. member → security 의존은 기존 선례(JwtTokenProvider·RefreshTokenStore 직접 참조)로
 * 이미 허용된 방향이다.
 *
 * <p>발급 로직은 {@link AuthTokenIssuer} 공용 경로로 위임 — API와 중복 없음.
 * 로그아웃 store 작업({@code deleteRefresh + blacklistAccess})은 이 서비스에서 직접 수행.
 *
 * <p>아키텍처 규칙: 서블릿/web 타입(HttpServletRequest/Response, Cookie)은 이 클래스에 들어오지 않는다.
 * 쿠키 I/O는 web 컨트롤러({@code CookieLoginViewController})가 {@code AuthCookies}를 통해 직접 수행한다.
 *
 * <p>설계 노트: plan §2는 ViewAuthService(member/service) 직접 주입을 명시했으나
 * WebModuleStructureTest·ModularityTests(web→member.service 금지, facade는 서블릿 타입 비노출) 위반으로
 * spi facade 경유 + 쿠키 I/O를 web으로 이관하는 방향으로 구현했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewAuthService implements ViewAuthFacade {

    private final MemberService memberService;
    private final AuthTokenIssuer authTokenIssuer;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * {@inheritDoc}
     *
     * <p>처리 순서:
     * <ol>
     *   <li>자격증명 검증 ({@link MemberService#authenticate}) — 실패 시 {@link com.shop.shop.common.exception.InvalidCredentialsException} 전파</li>
     *   <li>{@link AuthTokenIssuer#issue} — access + refresh 발급 + refresh Redis 저장</li>
     *   <li>{@link MemberService#recordLoginByEmail} — last_login_at 갱신</li>
     * </ol>
     */
    @Override
    public AuthTokenIssuer.IssuedTokens login(String email, String rawPassword) {
        User user = memberService.authenticate(email, rawPassword);

        List<String> roles = List.of(user.getRole().authority());
        AuthTokenIssuer.IssuedTokens issued = authTokenIssuer.issue(user.getId(), user.getEmail(), roles);

        memberService.recordLoginByEmail(user.getEmail());

        log.debug("View 로그인 완료: userId={}, email={}", user.getId(), user.getEmail());
        return issued;
    }

    /**
     * {@inheritDoc}
     *
     * <p>처리 순서:
     * <ol>
     *   <li>access 토큰에서 jti/userId 추출</li>
     *   <li>{@link RefreshTokenStore#deleteRefresh} — refresh revoke</li>
     *   <li>{@link RefreshTokenStore#blacklistAccess} — access 즉시 무효화</li>
     * </ol>
     *
     * <p>access 토큰이 null이거나 파싱 실패(만료·위조)한 경우 store 작업을 건너뛴다(fail-safe).
     */
    @Override
    public void revoke(String accessToken) {
        if (accessToken == null) {
            log.debug("revoke: access token null — store 작업 건너뜀");
            return;
        }
        try {
            Claims claims = jwtTokenProvider.parse(accessToken);
            long userId = jwtTokenProvider.extractUserId(claims);
            String jti = jwtTokenProvider.extractJti(claims);

            refreshTokenStore.deleteRefresh(userId);
            refreshTokenStore.blacklistAccess(jti, jwtTokenProvider.remainingTtl(claims));

            log.debug("View revoke 완료: userId={}, jti={}", userId, jti);
        } catch (InvalidTokenException e) {
            // 만료/위조된 access token이어도 fail-safe
            log.debug("revoke: access token 파싱 실패 — store 작업 건너뜀: {}", e.getMessage());
        }
    }
}
