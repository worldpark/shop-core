package com.shop.shop.member;

import com.shop.shop.member.controller.AdminMemberRestController;
import com.shop.shop.web.member.AdminMemberViewController;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.AdminMemberServiceResponse;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 회원 관련 빈 운영 배선 회귀 방지 테스트 (P1/testing-rule).
 *
 * <p>FakeRefreshTokenStore를 @Import해 Redis 미기동 환경에서도 컨텍스트가 기동된다.
 * MemberRepository, MemberUserDetailsService는 @MockitoBean으로 JPA/DB 의존을 격리한다.
 *
 * <p>신규 진입 빈(AdminMemberRestController, AdminMemberServiceResponse, AdminMemberViewController)이
 * 운영 컴포넌트 스캔에서 실제로 등록되는지 단언한다.
 * fake가 신규 배선을 가리지 않음을 확인한다 (006 RefreshTokenStoreWiringTest 패턴 계승).
 *
 * <p>RefreshTokenStore의 운영 구현(RedisRefreshTokenStore) 배선은
 * {@code RefreshTokenStoreWiringTest}에서 이미 보장됨 — 이 테스트는 변경하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminMemberWiringTest {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    MemberRepository memberRepository;

    @MockitoBean
    MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    CategoryRepository categoryRepository;

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ProductOptionRepository productOptionRepository;

    @MockitoBean
    OptionValueRepository optionValueRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    ProductImageRepository productImageRepository;

    @Test
    @DisplayName("운영 배선: AdminMemberRestController 빈이 컨텍스트에 등록된다")
    void adminMemberRestController_bean_is_registered() {
        AdminMemberRestController bean = context.getBean(AdminMemberRestController.class);
        assertThat(bean).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: AdminMemberServiceResponse 빈이 컨텍스트에 등록된다")
    void adminMemberServiceResponse_bean_is_registered() {
        AdminMemberServiceResponse bean = context.getBean(AdminMemberServiceResponse.class);
        assertThat(bean).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: AdminMemberViewController 빈이 컨텍스트에 등록된다")
    void adminMemberViewController_bean_is_registered() {
        AdminMemberViewController bean = context.getBean(AdminMemberViewController.class);
        assertThat(bean).isNotNull();
    }
}
