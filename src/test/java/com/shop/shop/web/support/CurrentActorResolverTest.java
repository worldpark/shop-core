package com.shop.shop.web.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentActorResolverTest {

    private final CurrentActorResolver resolver = new CurrentActorResolver();

    @Test
    @DisplayName("resolve — username을 actor email로 사용한다")
    void resolve_uses_authentication_name_as_email() {
        Authentication auth = authentication("seller@example.com", "ROLE_SELLER");

        CurrentActor actor = resolver.resolve(auth);

        assertThat(actor.email()).isEqualTo("seller@example.com");
    }

    @Test
    @DisplayName("resolve — ROLE_ADMIN 직접 보유 시 admin=true")
    void resolve_admin_when_role_admin_directly_granted() {
        Authentication auth = authentication("admin@example.com", "ROLE_ADMIN");

        CurrentActor actor = resolver.resolve(auth);

        assertThat(actor.admin()).isTrue();
    }

    @Test
    @DisplayName("resolve — ROLE_SELLER만 있으면 admin=false")
    void resolve_not_admin_for_seller_only() {
        Authentication auth = authentication("seller@example.com", "ROLE_SELLER");

        CurrentActor actor = resolver.resolve(auth);

        assertThat(actor.admin()).isFalse();
    }

    private Authentication authentication(String username, String authority) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "password",
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
