package com.shop.shop.member.dto;

import com.shop.shop.member.domain.Role;
import jakarta.validation.constraints.NotNull;

/**
 * 관리자 회원 권한 변경 REST PATCH 요청 DTO.
 *
 * <p>{@code Role} enum 역직렬화로 미정의 값은 400({@code HttpMessageNotReadableException}).
 * SELLER/CONSUMER만 허용·ADMIN 거부는 {@code MemberService.changeRole} 불변식이 최종 책임.
 *
 * @param role 변경할 권한 (not null 필수)
 */
public record RoleChangeRequest(
        @NotNull(message = "role은 필수입니다.") Role role
) {
}
