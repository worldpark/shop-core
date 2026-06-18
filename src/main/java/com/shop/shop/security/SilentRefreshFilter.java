package com.shop.shop.security;

import com.shop.shop.common.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * View 체인 전용 무음(silent) refresh 필터.
 * OncePerRequestFilter — 요청당 1회 실행, {@link JwtAuthenticationFilter}(View) 앞에 배치.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>SecurityContext가 이미 인증됨 → 통과</li>
 *   <li>access 쿠키 없음 → 통과 (refresh 시도 불필요)</li>
 *   <li>access 유효 → 통과 (후속 JWT 필터가 인증 처리)</li>
 *   <li>access 만료/무효 + refresh 쿠키 유효:
 *       <ul>
 *         <li>refresh parse + matchesRefresh 확인</li>
 *         <li>OK → AuthTokenIssuer로 <b>새 access만 발급</b>(refresh 회전 없음)</li>
 *         <li>AuthCookies.writeAccess로 새 access 쿠키 설정</li>
 *         <li>SecurityContext에 email principal 인증 설정</li>
 *       </ul>
 *   </li>
 *   <li>refresh 만료/불일치 → 미인증 → 후속 JWT 필터 통과 후 EntryPoint 302 /login</li>
 * </ol>
 *
 * <p>refresh 회전 비활성: 다중 탭 동시 만료 시 각 탭이 독립 성공(멱등, plan §1.4).
 * 탈퇴 사용자 차단: logout 시 deleteRefresh로 hash 삭제 → matchesRefresh=false.
 */
@Slf4j
@RequiredArgsConstructor
public class SilentRefreshFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthTokenIssuer authTokenIssuer;
    private final AuthCookies authCookies;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // (1) 이미 인증됨 → 통과
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // (2) access 쿠키 없음 → refresh 시도 불필요
        String accessToken = authCookies.readAccess(request);
        if (accessToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // (3) access 유효 → 후속 JWT 인증 필터에 위임
        try {
            jwtTokenProvider.parse(accessToken);
            filterChain.doFilter(request, response);
            return;
        } catch (InvalidTokenException e) {
            log.debug("access token 만료/무효 — silent refresh 시도: {}", e.getMessage());
        }

        // (4) access 만료/무효 → refresh 검증
        String refreshToken = authCookies.readRefresh(request);
        if (refreshToken == null) {
            log.debug("refresh 쿠키 없음 → 미인증");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims refreshClaims = jwtTokenProvider.parse(refreshToken);
            long userId = jwtTokenProvider.extractUserId(refreshClaims);

            if (!refreshTokenStore.matchesRefresh(userId, refreshToken)) {
                log.debug("refresh token 불일치(탈퇴/재사용 차단) → 미인증: userId={}", userId);
                filterChain.doFilter(request, response);
                return;
            }

            // 만료된 access token에서 email/roles 추출 (서명 유효 + 만료만인 경우)
            Claims expiredAccessClaims = jwtTokenProvider.parseIgnoreExpiry(accessToken);
            if (expiredAccessClaims == null) {
                log.debug("만료된 access token 클레임 추출 실패(위조 의심) → 미인증");
                filterChain.doFilter(request, response);
                return;
            }

            String email = jwtTokenProvider.extractEmail(expiredAccessClaims);
            List<String> roles = jwtTokenProvider.extractRoles(expiredAccessClaims);

            if (email == null || email.isBlank()) {
                log.debug("access token에 email 클레임 없음 → 미인증");
                filterChain.doFilter(request, response);
                return;
            }

            // 새 access만 발급 — refresh store 미변경(refresh 회전 없음, 다중 탭 멱등 §1.4)
            String newAccessToken = authTokenIssuer.issueAccessOnly(userId, email, roles);

            // 새 access 쿠키 응답에 설정
            authCookies.writeAccess(response, newAccessToken);

            // SecurityContext에 email principal 인증 설정 (View 체인 principal 계약 유지)
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("무음 refresh 완료: userId={}, email={}", userId, email);

        } catch (InvalidTokenException e) {
            log.debug("refresh token 만료/무효 → 미인증: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
