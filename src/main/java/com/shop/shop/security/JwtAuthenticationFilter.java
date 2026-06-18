package com.shop.shop.security;

import com.shop.shop.common.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * JWT 인증 필터 — 토큰 소스·principal 전략 주입으로 체인별 2개 빈 운용.
 * OncePerRequestFilter — 요청당 1회 실행.
 *
 * <p><b>API 체인 빈 ({@code apiJwtAuthenticationFilter})</b>:
 * <ul>
 *   <li>토큰 소스: Authorization: Bearer 헤더</li>
 *   <li>principal: userId(Long) — REST 사용처({@code (long) authentication.getPrincipal()}) 무회귀</li>
 * </ul>
 *
 * <p><b>View 체인 빈 ({@code viewJwtAuthenticationFilter})</b>:
 * <ul>
 *   <li>토큰 소스: {@code access_token} 쿠키</li>
 *   <li>principal: {@code getName()}=email — {@link com.shop.shop.web.support.CurrentActorResolver} 무회귀</li>
 * </ul>
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>tokenExtractor로 토큰 추출 (없으면 통과 → EntryPoint 위임)</li>
 *   <li>JwtTokenProvider.parse() — 만료/위조/형식 오류 → SecurityContext 미설정</li>
 *   <li>RefreshTokenStore.isBlacklisted(jti) — blacklist → SecurityContext 미설정</li>
 *   <li>유효 → principalFactory로 Authentication 생성 → SecurityContext 설정</li>
 * </ol>
 *
 * <p>parse/blacklist 실패 시 SecurityContext를 설정하지 않고 다음 필터로 위임.
 * 보호 경로는 SecurityConfig의 authenticated() 규칙이 EntryPoint를 트리거한다.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 요청에서 JWT 문자열을 추출하는 전략.
     * API: Authorization 헤더 / View: access_token 쿠키
     */
    private final Function<HttpServletRequest, String> tokenExtractor;

    /**
     * Claims에서 Authentication을 생성하는 전략.
     * API: principal=userId(Long) / View: principal getName()=email
     */
    private final Function<Claims, Authentication> principalFactory;

    /**
     * 전략 주입 생성자.
     *
     * @param jwtTokenProvider  JWT 파싱·검증
     * @param refreshTokenStore blacklist 조회
     * @param tokenExtractor    토큰 소스 전략 (헤더 / 쿠키)
     * @param principalFactory  principal 빌더 전략 (userId / email)
     */
    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore,
            Function<HttpServletRequest, String> tokenExtractor,
            Function<Claims, Authentication> principalFactory) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.tokenExtractor = tokenExtractor;
        this.principalFactory = principalFactory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = tokenExtractor.apply(request);

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtTokenProvider.parse(token);
                String jti = jwtTokenProvider.extractJti(claims);

                // blacklist 조회 — logout된 access token 차단 (매 요청 Redis 조회, View·API 동일)
                if (refreshTokenStore.isBlacklisted(jti)) {
                    log.debug("blacklist 등록된 토큰 요청 차단: jti={}", jti);
                    // SecurityContext 미설정 → EntryPoint가 처리
                } else {
                    Authentication authentication = principalFactory.apply(claims);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("JWT 인증 설정: principal={}", authentication.getName());
                }
            } catch (InvalidTokenException e) {
                log.debug("JWT 인증 실패: {}", e.getMessage());
                // SecurityContext 미설정 → EntryPoint가 처리
            }
        }

        filterChain.doFilter(request, response);
    }

    // =========================================================
    // 정적 팩토리 — 토큰 소스 전략
    // =========================================================

    /**
     * API용 토큰 소스: Authorization: Bearer 헤더.
     */
    public static Function<HttpServletRequest, String> bearerHeaderExtractor() {
        return request -> {
            String header = request.getHeader("Authorization");
            if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
                return header.substring(BEARER_PREFIX.length());
            }
            return null;
        };
    }

    /**
     * View용 토큰 소스: access_token 쿠키.
     */
    public static Function<HttpServletRequest, String> accessTokenCookieExtractor() {
        return request -> {
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return null;
            }
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if (AuthCookies.ACCESS_TOKEN.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
            return null;
        };
    }

    // =========================================================
    // 정적 팩토리 — principal 빌더 전략
    // =========================================================

    /**
     * API용 principal: userId(Long).
     * REST 사용처: {@code (long) authentication.getPrincipal()} — 무회귀 필수.
     */
    public static Function<Claims, Authentication> userIdPrincipalFactory(JwtTokenProvider provider) {
        return claims -> {
            long userId = provider.extractUserId(claims);
            List<String> roles = provider.extractRoles(claims);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            return new UsernamePasswordAuthenticationToken(userId, null, authorities);
        };
    }

    /**
     * View용 principal: getName()=email.
     * {@link com.shop.shop.web.support.CurrentActorResolver}가 {@code auth.getName()} → email 의존.
     */
    public static Function<Claims, Authentication> emailPrincipalFactory(JwtTokenProvider provider) {
        return claims -> {
            String email = provider.extractEmail(claims);
            List<String> roles = provider.extractRoles(claims);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            // principal = email(String), getName() = email
            return new UsernamePasswordAuthenticationToken(email, null, authorities);
        };
    }
}
