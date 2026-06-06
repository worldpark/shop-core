package com.shop.shop.web.support;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Spring Security {@link Authentication}을 View facade 호출용 actor 컨텍스트로 변환한다.
 *
 * <p>ROLE_ADMIN은 RoleHierarchy 함의가 아니라 원본 authority 직접 보유 여부로 판정한다.
 */
@Component
public class CurrentActorResolver {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    public CurrentActor resolve(Authentication auth) {
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));

        return new CurrentActor(auth.getName(), admin);
    }
}
