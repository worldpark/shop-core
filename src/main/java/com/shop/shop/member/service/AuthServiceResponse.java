package com.shop.shop.member.service;

import com.shop.shop.common.exception.InvalidTokenException;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.LoginRequest;
import com.shop.shop.member.dto.RefreshRequest;
import com.shop.shop.member.dto.TokenResponse;
import com.shop.shop.security.JwtProperties;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.RefreshTokenStore;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 인증 REST 응답 조합 전용 ServiceResponse 레이어.
 * View/Scheduler/EventListener에서는 사용하지 않는다 (architecture-rule).
 *
 * <p>비즈니스 로직은 하위 Service(MemberService, JwtTokenProvider, RefreshTokenStore)에 위임.
 * Entity는 직접 반환하지 않고 DTO(TokenResponse)로 변환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceResponse {

    private final MemberService memberService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtProperties jwtProperties;

    /**
     * 로그인 처리.
     * 자격증명 검증 → access/refresh 발급 → Redis에 refresh hash 저장 → TokenResponse 반환.
     *
     * @param request 로그인 요청(email, password)
     * @return TokenResponse (access, refresh, "Bearer", expiresIn)
     */
    public TokenResponse login(LoginRequest request) {
        User user = memberService.authenticate(request.email(), request.password());

        String accessToken = jwtTokenProvider.createAccess(
                user.getId(), user.getEmail(), List.of(user.getRole().authority()));
        String refreshToken = jwtTokenProvider.createRefresh(user.getId());

        refreshTokenStore.storeRefresh(user.getId(), refreshToken, jwtProperties.refreshTtl());

        long expiresIn = jwtProperties.accessTtl().toSeconds();
        return TokenResponse.of(accessToken, refreshToken, expiresIn);
    }

    /**
     * Access token 재발급.
     * refresh token 검증 → Redis hash 비교 → 새 access token 발급 (refresh 유지).
     *
     * @param request 재발급 요청(refreshToken)
     * @return TokenResponse (새 access, 기존 refresh, "Bearer", expiresIn)
     */
    public TokenResponse refresh(RefreshRequest request) {
        String refreshToken = request.refreshToken();

        // refresh token 서명/만료 검증 (InvalidTokenException 발생 시 그대로 전파)
        Claims claims = jwtTokenProvider.parse(refreshToken);
        long userId = jwtTokenProvider.extractUserId(claims);

        // Redis 저장값과 hash 비교 — logout/재사용/탈취 차단
        if (!refreshTokenStore.matchesRefresh(userId, refreshToken)) {
            throw new InvalidTokenException("refresh token이 유효하지 않거나 이미 사용되었습니다.");
        }

        User user = memberService.getById(userId);

        // 탈퇴 사용자 refresh 재발급 거부 — deleteRefresh로 hash가 이미 삭제되어 자연 실패하나 의도 명시(C 정정)
        if (!user.isActive()) {
            throw new InvalidTokenException("탈퇴한 계정은 토큰을 재발급할 수 없습니다.");
        }

        String newAccessToken = jwtTokenProvider.createAccess(
                user.getId(), user.getEmail(), List.of(user.getRole().authority()));

        long expiresIn = jwtProperties.accessTtl().toSeconds();
        return TokenResponse.of(newAccessToken, refreshToken, expiresIn);
    }

    /**
     * 로그아웃 처리.
     * access token에서 userId/jti 추출 → refresh 삭제 + access blacklist 등록 → 204.
     *
     * @param bearerAccessToken Authorization 헤더의 Bearer 토큰 문자열 (Bearer prefix 포함)
     */
    public void logout(String bearerAccessToken) {
        if (bearerAccessToken == null || !bearerAccessToken.startsWith("Bearer ")) {
            throw new InvalidTokenException("Authorization Bearer 토큰이 필요합니다.");
        }
        String accessToken = bearerAccessToken.substring("Bearer ".length());

        Claims claims = jwtTokenProvider.parse(accessToken);
        long userId = jwtTokenProvider.extractUserId(claims);
        String jti = jwtTokenProvider.extractJti(claims);

        refreshTokenStore.deleteRefresh(userId);
        refreshTokenStore.blacklistAccess(jti, jwtTokenProvider.remainingTtl(claims));

        log.debug("로그아웃 완료: userId={}, jti={}", userId, jti);
    }
}
