package com.shop.shop.security;

import com.shop.shop.common.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT Bearer token 인증 필터.
 * OncePerRequestFilter — 요청당 1회 실행.
 *
 * <p>처리 흐름:
 * 1. Authorization: Bearer {token} 헤더 추출 (없으면 통과 → EntryPoint가 401 처리)
 * 2. JwtTokenProvider.parse() — 만료/위조/형식 오류 → SecurityContext 미설정 (EntryPoint 위임)
 * 3. RefreshTokenStore.isBlacklisted(jti) — blacklist 등록 → SecurityContext 미설정
 * 4. 유효 → UsernamePasswordAuthenticationToken 생성 → SecurityContext 설정
 *
 * <p>parse/blacklist 실패 시 SecurityContext를 설정하지 않고 다음 필터로 위임.
 * 보호 경로는 SecurityConfig의 authenticated() 규칙이 EntryPoint(401)를 트리거한다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtTokenProvider.parse(token);
                String jti = jwtTokenProvider.extractJti(claims);

                // blacklist 조회 — logout된 access token 차단
                if (refreshTokenStore.isBlacklisted(jti)) {
                    log.debug("blacklist 등록된 토큰 요청 차단: jti={}", jti);
                    // SecurityContext 미설정 → EntryPoint가 401 처리
                } else {
                    long userId = jwtTokenProvider.extractUserId(claims);
                    List<String> roles = jwtTokenProvider.extractRoles(claims);
                    List<SimpleGrantedAuthority> authorities = roles.stream()
                            .map(SimpleGrantedAuthority::new)
                            .toList();

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("JWT 인증 설정: userId={}, roles={}", userId, roles);
                }
            } catch (InvalidTokenException e) {
                log.debug("JWT 인증 실패: {}", e.getMessage());
                // SecurityContext 미설정 → EntryPoint가 401 처리
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Authorization 헤더에서 Bearer token 추출.
     *
     * @return token 문자열 또는 null
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
