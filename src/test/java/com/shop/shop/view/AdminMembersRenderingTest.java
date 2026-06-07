package com.shop.shop.view;

import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.spi.AdminMemberFacade;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 회원 관리 화면 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿(templates/admin/members.html)이 레이아웃·프래그먼트와 함께
 * 올바르게 렌더링되는지 검증한다.
 *
 * <p>AdminMemberFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 * (원래 MemberService를 직접 Mock하던 방식에서 facade Mock 방식으로 전환)
 *
 * <p>검증 항목:
 * <ul>
 *   <li>200 응답 + 검색 폼 마커(keyword 입력/role 셀렉트)</li>
 *   <li>회원 목록 테이블 헤더(이메일/이름/권한/가입일)</li>
 *   <li>권한 변경 폼 마커(action /role, role 셀렉트 SELLER·CONSUMER 옵션, _csrf 주입)</li>
 *   <li>role 셀렉트에 ADMIN 옵션 부재 (Constraint)</li>
 *   <li>nav '홈' 마커 + footer 마커 (LayoutRenderingTest 비파괴)</li>
 *   <li>민감정보(passwordHash) 본문 미포함</li>
 * </ul>
 *
 * <p>패턴: @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")
 *         + @Import(FakeRefreshTokenStore) + @MockitoBean MemberRepository, MemberUserDetailsService
 *         (LayoutRenderingTest 컨벤션 준수)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminMembersRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private AdminMemberFacade adminMemberFacade;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private OptionValueRepository optionValueRepository;

    @MockitoBean
    private ProductVariantRepository productVariantRepository;

    @MockitoBean
    private ProductImageRepository productImageRepository;

    @MockitoBean
    private CartRepository cartRepository;

    @MockitoBean
    private CartItemRepository cartItemRepository;

    @MockitoBean
    private InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    private OrderRepository orderRepository;

    /** 테스트용 비밀번호 hash — 본문에 절대 노출되면 안 됨 */
    private static final String SENSITIVE_PASSWORD_HASH = "SUPER_SECRET_HASH_SHOULD_NOT_APPEAR";

    @BeforeEach
    void setUp() {
        // stub: searchMembers → 회원 1명이 포함된 페이지 반환 (MemberSummaryResponse DTO 직접 사용)
        MemberSummaryResponse member = new MemberSummaryResponse(
                42L, "user@example.com", "홍길동", "SELLER", Instant.now());
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminMemberFacade.searchMembers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(member), pageable, 1L));
    }

    // ============================================================
    // GET /admin/members — 200, 본문 마커 검증
    // ============================================================

    @Test
    @DisplayName("(R1) GET /admin/members — ADMIN → 200, 검색 폼 마커 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_renders_search_form() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 검색 폼: keyword 입력 필드
        assertThat(body).as("keyword 입력 필드가 렌더링되어야 함")
                .contains("name=\"keyword\"");

        // 검색 폼: role 셀렉트 (GET 검색 폼, 전체 옵션 포함)
        assertThat(body).as("role 셀렉트가 렌더링되어야 함")
                .contains("id=\"role\"");

        // 검색 폼 action
        assertThat(body).as("검색 폼 action이 /admin/members 여야 함")
                .contains("/admin/members");
    }

    @Test
    @DisplayName("(R2) GET /admin/members — ADMIN → 200, 회원 목록 테이블 헤더 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_renders_table_headers() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 테이블 헤더: 이메일/이름/권한/가입일
        assertThat(body).as("이메일 컬럼 헤더가 있어야 함").contains("이메일");
        assertThat(body).as("이름 컬럼 헤더가 있어야 함").contains("이름");
        assertThat(body).as("권한 컬럼 헤더가 있어야 함").contains("권한");
        assertThat(body).as("가입일 컬럼 헤더가 있어야 함").contains("가입일");
    }

    @Test
    @DisplayName("(R3) GET /admin/members — ADMIN → 200, 회원 데이터 행 렌더링 (stub 데이터 포함)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_renders_member_row() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // stub에서 설정한 회원 데이터가 본문에 포함되어야 함
        assertThat(body).as("stub 회원 이메일이 렌더링되어야 함")
                .contains("user@example.com");
        assertThat(body).as("stub 회원 이름이 렌더링되어야 함")
                .contains("홍길동");
        assertThat(body).as("stub 회원 권한이 렌더링되어야 함")
                .contains("SELLER");
    }

    @Test
    @DisplayName("(R4) GET /admin/members — ADMIN → 200, 권한 변경 폼: action /role 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_renders_role_change_form_action() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 권한 변경 폼 action: /admin/members/{memberId}/role
        assertThat(body).as("권한 변경 폼 action에 /role이 포함되어야 함")
                .contains("/role");

        // th:action 자동 _csrf 주입 확인 (POST 폼에 _csrf hidden field)
        assertThat(body).as("권한 변경 POST 폼에 _csrf 토큰이 자동 주입되어야 함")
                .contains("_csrf");
    }

    @Test
    @DisplayName("(R5) GET /admin/members — 권한 변경 role 셀렉트에 SELLER 옵션 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_role_change_select_contains_seller() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).as("권한 변경 셀렉트에 SELLER 옵션이 있어야 함")
                .contains("value=\"SELLER\"");
    }

    @Test
    @DisplayName("(R6) GET /admin/members — 권한 변경 role 셀렉트에 CONSUMER 옵션 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_role_change_select_contains_consumer() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).as("권한 변경 셀렉트에 CONSUMER 옵션이 있어야 함")
                .contains("value=\"CONSUMER\"");
    }

    @Test
    @DisplayName("(R7) GET /admin/members — 권한 변경 role 셀렉트에 ADMIN 옵션이 없음 (Constraint)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_role_change_select_does_not_contain_admin_option() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // role-change-form class 내에 value="ADMIN" 옵션이 없음을 단언.
        int roleChangeFormIdx = body.indexOf("role-change-form");
        assertThat(roleChangeFormIdx).as("role-change-form이 본문에 존재해야 함").isGreaterThanOrEqualTo(0);

        // role-change-form 부분 추출 후 ADMIN value 옵션 부재 확인
        String roleChangeFormSection = body.substring(roleChangeFormIdx);
        int formEndIdx = roleChangeFormSection.indexOf("</form>");
        String firstRoleChangeForm = formEndIdx > 0
                ? roleChangeFormSection.substring(0, formEndIdx)
                : roleChangeFormSection.substring(0, Math.min(500, roleChangeFormSection.length()));

        assertThat(firstRoleChangeForm)
                .as("권한 변경 폼 셀렉트에 ADMIN 옵션(value=\"ADMIN\")이 없어야 함 (Constraint)")
                .doesNotContain("value=\"ADMIN\"");
    }

    @Test
    @DisplayName("(R8) GET /admin/members — nav '홈' 마커 포함 (LayoutRenderingTest 비파괴)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_contains_nav_home_marker() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).as("nav '홈' 마커가 있어야 함 (비파괴)")
                .contains("홈");
    }

    @Test
    @DisplayName("(R9) GET /admin/members — footer 마커 포함 (LayoutRenderingTest 비파괴)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_contains_footer_marker() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).as("footer 마커가 있어야 함 (비파괴)")
                .contains("2026 shop-core. All rights reserved.");
    }

    @Test
    @DisplayName("(R10) GET /admin/members — 민감정보(실제 해시값) 본문 미포함 (Constraint)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_does_not_expose_sensitive_info() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // MemberSummaryResponse DTO에 passwordHash 필드가 없으므로 절대 노출되면 안 됨
        assertThat(body).as("민감 해시 값이 본문에 노출되면 안 됨 (DTO에 passwordHash 필드 없음)")
                .doesNotContain(SENSITIVE_PASSWORD_HASH);
    }

    @Test
    @DisplayName("(R11) GET /admin/members — CSS 링크(/css/app.css) 포함 (layout/base 연계)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_admin_members_contains_css_link() throws Exception {
        String body = mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).as("/css/app.css 링크가 있어야 함 (layout/base 연계)")
                .contains("/css/app.css");
    }
}
