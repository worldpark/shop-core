package com.shop.shop.member.dto;

/**
 * 토큰 발급/재발급 응답 DTO.
 *
 * @param accessToken  JWT access token
 * @param refreshToken JWT refresh token
 * @param tokenType    항상 "Bearer"
 * @param expiresIn    access token 만료까지 남은 초(seconds)
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {

    public static TokenResponse of(String accessToken, String refreshToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
