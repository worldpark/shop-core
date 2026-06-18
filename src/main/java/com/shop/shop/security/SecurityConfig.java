package com.shop.shop.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.CookieRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.util.Set;

/**
 * 전역 보안 설정 — 3체인(actuator/REST/View) 구조.
 *
 * <p>Actuator 체인 (@Order(0)):
 * - CSRF disable + STATELESS. 무변경.
 *
 * <p>REST 체인 (@Order(1), securityMatcher /api/v1/**):
 * - STATELESS, CSRF disable (JWT 기반 — CSRF 비대상)
 * - {@link #apiJwtAuthenticationFilter}: Bearer 헤더 소스 + principal=userId(Long)
 * - POST /login, POST /refresh: permitAll
 * - 그 외 /api/v1/**: authenticated
 * - 미인증: RestAuthenticationEntryPoint (401 JSON). 무변경.
 *
 * <p>View 체인 (@Order(2), 나머지 전체):
 * - JWT 쿠키 인증 + STATELESS (formLogin·세션 생성 제거, 054 cutover)
 * - {@link #silentRefreshFilter}: access 만료 + refresh 유효 시 무음 재발급 (View JWT 필터 앞)
 * - {@link #viewJwtAuthenticationFilter}: access_token 쿠키 소스 + principal getName()=email
 * - POST /login: {@link com.shop.shop.web.auth.CookieLoginViewController}
 * - POST /logout: {@link com.shop.shop.web.auth.CookieLoginViewController}
 * - 미인증: LoginUrlAuthenticationEntryPoint 302 /login
 * - CSRF: CookieCsrfTokenRepository (052 유지)
 * - CookieRequestCache (052 유지)
 * - JSESSIONID 미생성 (STATELESS)
 *
 * <p>RoleHierarchy 빈(ROLE_ADMIN > ROLE_SELLER > ROLE_CONSUMER)은 두 체인이 공유.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class})
public class SecurityConfig {

    private static final String LOGIN_PAGE = "/login";

    /**
     * Actuator 관리 엔드포인트 전용 보안 체인 (@Order(0) — REST/View 체인보다 먼저 매칭).
     * 무변경.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(EndpointRequest.to("health")).permitAll()
                    .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    /**
     * REST API 보안 체인.
     * securityMatcher("/api/v1/**") — 이 체인이 먼저 매칭.
     * 필터 빈: apiJwtAuthenticationFilter (Bearer 헤더 소스 + principal=userId(Long)).
     * API 체인 authorize 규칙·RestAuthenticationEntryPoint/RestAccessDeniedHandler 무변경.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain restChain(
            HttpSecurity http,
            JwtAuthenticationFilter apiJwtAuthenticationFilter,
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
                    // 상품 리뷰 목록 공개 조회
                    .requestMatchers(HttpMethod.GET, "/api/v1/products/*/reviews").permitAll()
                    // 리뷰 쓰기 REST API — 최소 ROLE_CONSUMER
                    .requestMatchers("/api/v1/reviews/**").hasRole("CONSUMER")
                    // 관리자 전용 REST API
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    // 판매자 신청 REST API
                    .requestMatchers("/api/v1/seller-applications/**").authenticated()
                    // 판매자 이상 REST API
                    .requestMatchers("/api/v1/seller/**").hasRole("SELLER")
                    // 장바구니 REST API — 최소 ROLE_CONSUMER
                    .requestMatchers("/api/v1/cart/**").hasRole("CONSUMER")
                    // 쿠폰 REST API — 최소 ROLE_CONSUMER
                    .requestMatchers("/api/v1/coupons/**").hasRole("CONSUMER")
                    // 결제 경로
                    .requestMatchers("/api/v1/orders/*/payment").hasRole("CONSUMER")
                    // 취소 경로
                    .requestMatchers("/api/v1/orders/*/cancel").hasRole("CONSUMER")
                    // 주문 REST API — 최소 ROLE_CONSUMER
                    .requestMatchers("/api/v1/orders/**").hasRole("CONSUMER")
                    // 그 외 REST API는 인증 필요
                    .anyRequest().authenticated()
            )
            .addFilterBefore(apiJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(restAuthenticationEntryPoint)
                    .accessDeniedHandler(restAccessDeniedHandler)
            );

        return http.build();
    }

    /**
     * View(Thymeleaf) 보안 체인 — JWT 쿠키 인증 + STATELESS (formLogin·세션 생성 제거, 054 cutover).
     *
     * <p>주요 변경 (054):
     * <ul>
     *   <li>formLogin 제거 → POST /login은 {@link com.shop.shop.web.auth.CookieLoginViewController}</li>
     *   <li>sessionCreationPolicy(STATELESS) → JSESSIONID 미생성</li>
     *   <li>silentRefreshFilter + viewJwtAuthenticationFilter 추가</li>
     *   <li>미인증 EntryPoint: LoginUrlAuthenticationEntryPoint (302 /login)</li>
     * </ul>
     *
     * <p>무변경: CSRF(CookieCsrfTokenRepository), CookieRequestCache(052), userDetailsService,
     * authorize 매처 전체.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain viewChain(
            HttpSecurity http,
            UserDetailsService userDetailsService,
            JwtAuthenticationFilter viewJwtAuthenticationFilter,
            SilentRefreshFilter silentRefreshFilter,
            LoginUrlAuthenticationEntryPoint loginRedirectEntryPoint) throws Exception {

        http
            .authorizeHttpRequests(auth -> auth
                    // 공개 경로
                    .requestMatchers(HttpMethod.GET, LOGIN_PAGE).permitAll()
                    // POST /login — CookieLoginViewController (CSRF 보호 유지)
                    .requestMatchers(HttpMethod.POST, LOGIN_PAGE).permitAll()
                    // POST /logout — CookieLoginViewController (CSRF 보호 유지)
                    .requestMatchers(HttpMethod.POST, "/logout").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/assets/**", "/favicon.ico", "/error").permitAll()
                    // 회원가입 화면 공개
                    .requestMatchers(HttpMethod.GET, "/signup").permitAll()
                    .requestMatchers(HttpMethod.POST, "/signup").permitAll()
                    // 비밀번호 재설정 화면 공개
                    .requestMatchers(HttpMethod.GET, "/password-reset").permitAll()
                    .requestMatchers(HttpMethod.POST, "/password-reset").permitAll()
                    .requestMatchers(HttpMethod.GET, "/password-reset/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/password-reset/**").permitAll()
                    // 공개 상품 목록/상세 View
                    .requestMatchers(HttpMethod.GET, "/products").permitAll()
                    .requestMatchers(HttpMethod.GET, "/products/*").permitAll()
                    // 리뷰 View 경로 — 최소 ROLE_CONSUMER
                    .requestMatchers("/reviews/**").hasRole("CONSUMER")
                    // 계정 self-service View 경로
                    .requestMatchers("/account", "/account/**").authenticated()
                    // 관리자 전용 View 경로
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    // 판매자 신청 View 경로
                    .requestMatchers("/seller-applications/**").authenticated()
                    // 판매자 이상 View 경로
                    .requestMatchers("/seller/**").hasRole("SELLER")
                    // 장바구니 View 경로 — 최소 ROLE_CONSUMER
                    .requestMatchers("/cart", "/cart/**").hasRole("CONSUMER")
                    // 결제 경로
                    .requestMatchers("/orders/*/payment").hasRole("CONSUMER")
                    // 취소 경로
                    .requestMatchers("/orders/*/cancel").hasRole("CONSUMER")
                    // 주문/체크아웃 View 경로 — 최소 ROLE_CONSUMER
                    .requestMatchers("/checkout", "/orders", "/orders/**").hasRole("CONSUMER")
                    // 그 외 모든 경로는 인증 필요
                    .anyRequest().authenticated()
            )
            // STATELESS: 세션 생성 없음 → JSESSIONID 미생성 (054 cutover 핵심)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // View JWT 인증 필터: UsernamePasswordAuthenticationFilter 앞에
            .addFilterBefore(viewJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // 무음 refresh 필터: viewJwtAuthenticationFilter 앞에 (silent → JWT → UPAF 순)
            .addFilterBefore(silentRefreshFilter, JwtAuthenticationFilter.class)
            // 미인증: 302 /login (formLogin 제거 후 명시 EntryPoint)
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(loginRedirectEntryPoint)
            )
            // saved-request: CookieRequestCache (052 유지)
            .requestCache(rc -> rc.requestCache(htmlNavigationRequestCache()))
            // View formLogin 제거 (JWT 쿠키 인증으로 대체 — 054)
            // userDetailsService: 비밀번호 재설정 등 UserDetailsService 의존 유지
            .userDetailsService(userDetailsService)
            // CSRF: 쿠키 저장소 (052 유지).
            // sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy):
            //   Spring Security 기본 CsrfAuthenticationStrategy는 JWT 인증 설정 시마다
            //   (viewJwtAuthenticationFilter가 정적 자산 요청에서도 SecurityContext에 인증을 설정하므로)
            //   saveToken(null) → XSRF-TOKEN 쿠키 삭제를 반복한다.
            //   STATELESS JWT 앱에서는 session fixation이 없으므로 CSRF 토큰 회전이 불필요하다.
            //   NullAuthenticatedSessionStrategy를 지정해 인증 이벤트 시 토큰 회전을 비활성화한다.
            //   폼 기반 CSRF 보호(토큰 검증)는 완전 유지된다.
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
            )
            // 기본 LogoutFilter 비활성화 — JWT 쿠키 revoke/제거는 CookieLoginViewController가 수행(054)
            // Spring Security 6은 명시 없이도 LogoutFilter(/logout)를 기본 적용해 컨트롤러 도달을 차단.
            // POST /logout이 CookieLoginViewController.logout()에 도달하려면 disable() 필수.
            .logout(logout -> logout.disable());

        return http.build();
    }

    /**
     * View 체인 미인증 진입점: 302 /login (formLogin 제거 후 명시 빈으로 전환).
     * API 체인은 RestAuthenticationEntryPoint (401 JSON) 유지 — 분리 확인.
     */
    @Bean
    public LoginUrlAuthenticationEntryPoint loginRedirectEntryPoint() {
        return new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE);
    }

    /**
     * HTML 네비게이션 요청만 저장하는 RequestCache (쿠키 기반, 052).
     * Accept 헤더가 text/html과 호환되는 요청만 SavedRequest로 저장한다.
     * 빈 등록: CookieLoginViewController에서 주입받아 SavedRequest 복귀에 사용.
     */
    @Bean
    public RequestCache htmlNavigationRequestCache() {
        MediaTypeRequestMatcher htmlMatcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        htmlMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        CookieRequestCache cookieRequestCache = new CookieRequestCache();
        cookieRequestCache.setRequestMatcher(htmlMatcher);
        return cookieRequestCache;
    }

    /**
     * BCrypt 비밀번호 인코더.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 역할 계층 설정: ROLE_ADMIN > ROLE_SELLER > ROLE_CONSUMER.
     * REST 체인·View 체인 모두 공유.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_SELLER
                ROLE_SELLER > ROLE_CONSUMER
                """);
    }

    /**
     * API 체인용 JWT 인증 필터 빈.
     * 토큰 소스: Authorization: Bearer 헤더.
     * principal: userId(Long) — REST 사용처 무회귀 ({@code (long) authentication.getPrincipal()}).
     */
    @Bean
    public JwtAuthenticationFilter apiJwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore) {
        return new JwtAuthenticationFilter(
                jwtTokenProvider,
                refreshTokenStore,
                JwtAuthenticationFilter.bearerHeaderExtractor(),
                JwtAuthenticationFilter.userIdPrincipalFactory(jwtTokenProvider)
        );
    }

    /**
     * View 체인용 JWT 인증 필터 빈.
     * 토큰 소스: access_token 쿠키.
     * principal: getName()=email — {@link com.shop.shop.web.support.CurrentActorResolver} 무회귀.
     */
    @Bean
    public JwtAuthenticationFilter viewJwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore) {
        return new JwtAuthenticationFilter(
                jwtTokenProvider,
                refreshTokenStore,
                JwtAuthenticationFilter.accessTokenCookieExtractor(),
                JwtAuthenticationFilter.emailPrincipalFactory(jwtTokenProvider)
        );
    }

    /**
     * View 체인용 무음 refresh 필터 빈.
     * access 만료 + refresh 유효 시 새 access 쿠키 발급 + SecurityContext 인증 설정.
     * viewJwtAuthenticationFilter 앞에 배치.
     */
    @Bean
    public SilentRefreshFilter silentRefreshFilter(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore,
            AuthTokenIssuer authTokenIssuer,
            AuthCookies authCookies) {
        return new SilentRefreshFilter(jwtTokenProvider, refreshTokenStore, authTokenIssuer, authCookies);
    }
}
