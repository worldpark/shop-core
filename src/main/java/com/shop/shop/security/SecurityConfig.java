package com.shop.shop.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 전역 보안 설정 — REST 체인(@Order(1)) + View 체인(@Order(2)) 분리.
 *
 * <p>REST 체인 (@Order(1), securityMatcher /api/v1/**):
 * - STATELESS, CSRF disable (JWT 기반 — CSRF 비대상)
 * - JwtAuthenticationFilter 추가
 * - POST /login, POST /refresh: permitAll
 * - 그 외 /api/v1/**: authenticated
 * - 미인증: RestAuthenticationEntryPoint (401 JSON)
 * - 권한 부족: RestAccessDeniedHandler (403 JSON)
 *
 * <p>View 체인 (@Order(2), 나머지 전체):
 * - formLogin(/login), logout(/logout), CSRF 활성, 세션 유지 (기존 비파괴)
 * - userDetailsService: MemberUserDetailsService (DB 기반, InMemory 제거)
 * - 미인증: 302 redirect → /login
 *
 * <p>RoleHierarchy 빈(ROLE_ADMIN > ROLE_SELLER > ROLE_CONSUMER)은 두 체인이 공유한다.
 *
 * <p>InMemoryUserDetailsManager + SecurityUserProperties 제거 (revision 001 + plan 1.3).
 * PasswordEncoder(BCrypt) 빈은 유지 (로그인 검증·향후 회원가입 공용).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class})
public class SecurityConfig {

    private static final String LOGIN_PAGE = "/login";

    /**
     * REST API 보안 체인.
     * securityMatcher("/api/v1/**") — 이 체인이 먼저 매칭.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain restChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler) throws Exception {

        http
            .securityMatcher("/api/v1/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // 로그인 / 토큰 재발급은 공개 (인증 불필요)
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                    // 비밀번호 재설정 (비로그인 — enumeration 방지, 토큰 capability 기반)
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/password-reset/request").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/password-reset/confirm").permitAll()
                    // 회원가입은 공개 (인증 불필요)
                    .requestMatchers(HttpMethod.POST, "/api/v1/members/signup").permitAll()
                    // 카테고리 목록 조회는 공개 (인증 불필요) — anyRequest 앞에 배치
                    .requestMatchers(HttpMethod.GET, "/api/v1/categories").permitAll()
                    // 공개 상품 목록/상세 API (인증 불필요) — anyRequest 앞에 배치
                    .requestMatchers(HttpMethod.GET, "/api/v1/products").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/products/*").permitAll()
                    // 관리자 전용 REST API (anyRequest 앞에 배치)
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    // 판매자 신청 REST API — 보안 floor authenticated (자격은 서비스 409 — Task 027 §1.1)
                    // admin REST(/api/v1/admin/**)가 이미 ADMIN 커버. consumer 신청만 의도 명시.
                    .requestMatchers("/api/v1/seller-applications/**").authenticated()
                    // 판매자 이상 REST API (ADMIN 함의) — anyRequest 앞에 배치
                    .requestMatchers("/api/v1/seller/**").hasRole("SELLER")
                    // 장바구니 REST API — 최소 ROLE_CONSUMER (SELLER/ADMIN은 역할 계층 함의)
                    .requestMatchers("/api/v1/cart/**").hasRole("CONSUMER")
                    // 결제 경로 — Task 016 명시 권한 (api-authorization-rule: ROLE_CONSUMER 최소, 소유권 서비스 레이어 검증)
                    // /api/v1/orders/**가 이미 CONSUMER 덮음 — 의도 명시 + 회귀 방지용 전용 matcher
                    .requestMatchers("/api/v1/orders/*/payment").hasRole("CONSUMER")
                    // 취소 경로 — Task 018 명시 권한 (api-authorization-rule: ROLE_CONSUMER 최소, 소유권 서비스 레이어 검증)
                    // /api/v1/orders/**가 이미 CONSUMER 덮음 — 의도 명시 + 회귀 방지용 전용 matcher
                    .requestMatchers("/api/v1/orders/*/cancel").hasRole("CONSUMER")
                    // 주문 REST API — 최소 ROLE_CONSUMER (SELLER/ADMIN은 역할 계층 함의)
                    .requestMatchers("/api/v1/orders/**").hasRole("CONSUMER")
                    // 그 외 REST API는 인증 필요
                    .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(restAuthenticationEntryPoint)
                    .accessDeniedHandler(restAccessDeniedHandler)
            );

        return http.build();
    }

    /**
     * View(Thymeleaf) 보안 체인 — 기존 formLogin/logout/CSRF/세션 유지 (비파괴).
     * userDetailsService는 DB 기반 MemberUserDetailsService로 교체 (InMemory 제거).
     */
    @Bean
    @Order(2)
    public SecurityFilterChain viewChain(
            HttpSecurity http,
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) throws Exception {

        http
            .authorizeHttpRequests(auth -> auth
                    // 공개 경로
                    .requestMatchers(HttpMethod.GET, LOGIN_PAGE).permitAll()
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/assets/**", "/favicon.ico", "/error").permitAll()
                    // 회원가입 화면 공개 (GET 표시 + POST 폼 제출 — CSRF 보호 유지)
                    .requestMatchers(HttpMethod.GET, "/signup").permitAll()
                    .requestMatchers(HttpMethod.POST, "/signup").permitAll()
                    // 비밀번호 재설정 화면 공개 (GET/POST 모두 — 비로그인 흐름)
                    .requestMatchers(HttpMethod.GET, "/password-reset").permitAll()
                    .requestMatchers(HttpMethod.POST, "/password-reset").permitAll()
                    .requestMatchers(HttpMethod.GET, "/password-reset/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/password-reset/**").permitAll()
                    // 공개 상품 목록/상세 View (인증 불필요) — anyRequest 앞에 배치
                    .requestMatchers(HttpMethod.GET, "/products").permitAll()
                    .requestMatchers(HttpMethod.GET, "/products/*").permitAll()
                    // 계정 self-service View 경로 (anyRequest 앞에 배치)
                    // /account, /account/** — authenticated (CONSUMER/SELLER/ADMIN 모두 본인 계정 관리)
                    // REST /api/v1/members/me/**는 anyRequest authenticated가 이미 커버 (별 matcher 불요)
                    .requestMatchers("/account", "/account/**").authenticated()
                    // 관리자 전용 View 경로 (anyRequest 앞에 배치)
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    // 판매자 신청 View 경로 — 보안 floor authenticated (자격은 서비스 409 — Task 027 §1.1)
                    // hasRole("CONSUMER") 금지: SELLER/ADMIN도 진입 가능(안내 화면 도달 보장)
                    .requestMatchers("/seller-applications/**").authenticated()
                    // 판매자 이상 View 경로 (ADMIN 함의) — anyRequest 앞에 배치
                    .requestMatchers("/seller/**").hasRole("SELLER")
                    // 장바구니 View 경로 — 최소 ROLE_CONSUMER (미인증은 formLogin이 302→/login)
                    .requestMatchers("/cart", "/cart/**").hasRole("CONSUMER")
                    // 결제 경로 — Task 016 명시 권한 (api-authorization-rule: ROLE_CONSUMER 최소, 소유권 서비스 레이어 검증)
                    // /orders/**가 이미 CONSUMER 덮음 — 의도 명시 + 회귀 방지용 전용 matcher
                    .requestMatchers("/orders/*/payment").hasRole("CONSUMER")
                    // 취소 경로 — Task 018 명시 권한 (api-authorization-rule: ROLE_CONSUMER 최소, 소유권 서비스 레이어 검증)
                    // /orders/**가 이미 CONSUMER 덮음 — 의도 명시 + 회귀 방지용 전용 matcher
                    .requestMatchers("/orders/*/cancel").hasRole("CONSUMER")
                    // 주문/체크아웃 View 경로 — 최소 ROLE_CONSUMER (미인증은 formLogin이 302→/login)
                    .requestMatchers("/checkout", "/orders", "/orders/**").hasRole("CONSUMER")
                    // 그 외 모든 경로는 인증 필요
                    .anyRequest().authenticated()
            )
            .formLogin(form -> form
                    .loginPage(LOGIN_PAGE)
                    .loginProcessingUrl(LOGIN_PAGE)
                    .defaultSuccessUrl("/", false)
                    .failureUrl(LOGIN_PAGE + "?error")
                    .permitAll()
            )
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl(LOGIN_PAGE + "?logout")
                    .permitAll()
            )
            .userDetailsService(userDetailsService);
        // CSRF: 기본 활성 유지 (폼 th:action 으로 _csrf 히든 자동 주입)

        return http.build();
    }

    /**
     * BCrypt 비밀번호 인코더.
     * 로그인 검증·향후 회원가입에 공용 사용.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 역할 계층 설정: ROLE_ADMIN > ROLE_SELLER > ROLE_CONSUMER.
     * REST 체인·View 체인 모두 공유.
     * ADMIN은 SELLER/CONSUMER 리소스 접근 가능, SELLER는 CONSUMER 리소스 접근 가능.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_SELLER
                ROLE_SELLER > ROLE_CONSUMER
                """);
    }

    /**
     * JWT 인증 필터 빈.
     * @Bean으로 명시 등록 — SecurityConfig가 JwtProperties, RefreshTokenStore에 의존하지 않도록 분리.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore) {
        return new JwtAuthenticationFilter(jwtTokenProvider, refreshTokenStore);
    }
}
