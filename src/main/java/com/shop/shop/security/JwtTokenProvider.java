package com.shop.shop.security;

import com.shop.shop.common.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT access/refresh token 발급·파싱·검증.
 *
 * <p>라이브러리: jjwt 0.12.x
 * <p>알고리즘: HS256 (대칭키)
 * <p>access token 클레임: sub(userId), email, roles(ROLE_* 목록), jti(UUID), iss, iat, exp
 * <p>refresh token 클레임: sub(userId), jti(UUID), iss, iat, exp (roles 미포함)
 *
 * <p>jjwt 0.12.x API 변경사항:
 * - 발급: Jwts.builder().signWith(key) (0.11의 signWith(key, alg) 통합)
 * - 파싱: Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
 *
 * <p>member 도메인 타입(User) 비의존 — security 모듈 순환 의존 차단.
 * 필요한 값(userId, email, roles)을 파라미터로 받는다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey secretKey;
    private final JwtProperties jwtProperties;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKey = Keys.hmacShaKeyFor(
                jwtProperties.secret().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Access token 발급.
     * 클레임: sub=userId, email, roles=[ROLE_*], jti=UUID, iss, iat, exp=now+accessTtl
     *
     * @param userId 회원 식별자
     * @param email  회원 이메일
     * @param roles  권한 목록 (예: ["ROLE_CONSUMER"])
     */
    public String createAccess(long userId, String email, List<String> roles) {
        Instant now = Instant.now();
        Instant exp = now.plus(jwtProperties.accessTtl());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
                .id(UUID.randomUUID().toString())
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Refresh token 발급.
     * 클레임: sub=userId, jti=UUID, iss, iat, exp=now+refreshTtl (roles 미포함)
     *
     * @param userId 회원 식별자
     */
    public String createRefresh(long userId) {
        Instant now = Instant.now();
        Instant exp = now.plus(jwtProperties.refreshTtl());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 토큰 파싱 및 검증.
     * 만료 / 서명 위조 / 형식 오류 → InvalidTokenException(401)
     *
     * @param token JWT 문자열
     * @return 검증된 Claims
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("JWT 만료: {}", e.getMessage());
            throw new InvalidTokenException("토큰이 만료되었습니다.");
        } catch (JwtException e) {
            log.debug("JWT 검증 실패: {}", e.getMessage());
            throw new InvalidTokenException();
        }
    }

    /** userId 추출 */
    public long extractUserId(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    /** roles 클레임 추출 (access token 전용) */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        Object roles = claims.get(CLAIM_ROLES);
        if (roles instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    /** jti(JWT ID) 추출 */
    public String extractJti(Claims claims) {
        return claims.getId();
    }

    /**
     * access token의 email 클레임 추출 (View principal 구성용).
     * access token에만 email 클레임이 존재한다 (refresh token에는 없음).
     *
     * @param claims 검증된 Claims
     * @return 회원 이메일 또는 null (refresh token 등 email 클레임 미포함 토큰)
     */
    public String extractEmail(Claims claims) {
        return claims.get(CLAIM_EMAIL, String.class);
    }

    /**
     * 만료된 토큰에서도 Claims를 추출한다 (무음 refresh 전용).
     *
     * <p>access 쿠키 만료 시 {@link SilentRefreshFilter}가 email/roles를 새 access 발급에 재사용한다.
     * 서명 검증은 수행하되 만료 예외는 무시하고 Claims를 반환한다.
     * 위조/형식 오류는 null 반환(호출자가 미인증 처리).
     *
     * @param token JWT 문자열 (만료됐어도 가능)
     * @return Claims 또는 null (위조/형식 오류)
     */
    @Nullable
    public Claims parseIgnoreExpiry(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // 만료됐지만 서명 유효 — Claims 반환
            return e.getClaims();
        } catch (JwtException e) {
            log.debug("토큰 위조/형식 오류 (parseIgnoreExpiry): {}", e.getMessage());
            return null;
        }
    }

    /**
     * access token의 잔여 만료 시간 계산.
     * blacklist TTL 설정에 사용 (음수이면 Duration.ZERO 반환).
     */
    public Duration remainingTtl(Claims claims) {
        Date exp = claims.getExpiration();
        if (exp == null) {
            return Duration.ZERO;
        }
        long remaining = exp.toInstant().toEpochMilli() - Instant.now().toEpochMilli();
        return remaining > 0 ? Duration.ofMillis(remaining) : Duration.ZERO;
    }
}
