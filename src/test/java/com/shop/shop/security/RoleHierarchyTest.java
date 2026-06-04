package com.shop.shop.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoleHierarchy 단위 테스트.
 * ROLE_ADMIN > ROLE_SELLER > ROLE_CONSUMER 계층 동작 검증.
 */
class RoleHierarchyTest {

    private RoleHierarchy roleHierarchy;

    @BeforeEach
    void setUp() {
        roleHierarchy = RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_SELLER
                ROLE_SELLER > ROLE_CONSUMER
                """);
    }

    @Test
    @DisplayName("ADMIN은 ROLE_ADMIN / ROLE_SELLER / ROLE_CONSUMER를 모두 함의한다")
    void admin_implies_all_roles() {
        Collection<SimpleGrantedAuthority> reachable = toReachable("ROLE_ADMIN");

        assertThat(reachable).extracting(SimpleGrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN", "ROLE_SELLER", "ROLE_CONSUMER");
    }

    @Test
    @DisplayName("SELLER는 ROLE_SELLER / ROLE_CONSUMER를 함의하지만 ROLE_ADMIN은 함의하지 않는다")
    void seller_implies_seller_and_consumer_but_not_admin() {
        Collection<SimpleGrantedAuthority> reachable = toReachable("ROLE_SELLER");

        assertThat(reachable).extracting(SimpleGrantedAuthority::getAuthority)
                .contains("ROLE_SELLER", "ROLE_CONSUMER")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    @DisplayName("CONSUMER는 ROLE_CONSUMER만 함의하고 상위 권한은 함의하지 않는다")
    void consumer_implies_only_consumer() {
        Collection<SimpleGrantedAuthority> reachable = toReachable("ROLE_CONSUMER");

        assertThat(reachable).extracting(SimpleGrantedAuthority::getAuthority)
                .contains("ROLE_CONSUMER")
                .doesNotContain("ROLE_ADMIN", "ROLE_SELLER");
    }

    @Test
    @DisplayName("ADMIN이 가진 모든 권한 수는 3개 이상이다")
    void admin_has_at_least_3_reachable_roles() {
        Collection<SimpleGrantedAuthority> reachable = toReachable("ROLE_ADMIN");
        assertThat(reachable).hasSizeGreaterThanOrEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private Collection<SimpleGrantedAuthority> toReachable(String role) {
        return (Collection<SimpleGrantedAuthority>) roleHierarchy.getReachableGrantedAuthorities(
                List.of(new SimpleGrantedAuthority(role))
        );
    }
}
