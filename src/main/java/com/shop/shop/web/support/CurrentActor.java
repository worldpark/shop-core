package com.shop.shop.web.support;

/**
 * View 진입점에서 도메인 facade로 넘기는 현재 행위자 컨텍스트.
 *
 * @param email form login session principal username(email)
 * @param admin ROLE_ADMIN 직접 보유 여부
 */
public record CurrentActor(
        String email,
        boolean admin
) {
}
