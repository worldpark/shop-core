package com.shop.shop.member;

import com.shop.shop.member.controller.MemberRestController;
import com.shop.shop.member.controller.MemberSignupViewController;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberServiceResponse;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원가입 관련 빈 운영 배선 회귀 방지 테스트 (P1/testing-rule).
 *
 * <p>FakeRefreshTokenStore를 @Import해 Redis 미기동 환경에서도 컨텍스트가 기동된다.
 * MemberRepository, MemberUserDetailsService는 @MockBean으로 JPA/DB 의존을 격리한다.
 *
 * <p>신규 진입 빈(MemberRestController, MemberServiceResponse, MemberSignupViewController)이
 * 운영 컴포넌트 스캔에서 실제로 등록되는지 단언한다.
 * fake가 신규 배선을 가리지 않음을 확인 (testing-rule P1 — fake 미import 없이 실 빈 확인).
 *
 * <p>RefreshTokenStore의 운영 구현(RedisRefreshTokenStore) 배선은
 * {@code RefreshTokenStoreWiringTest}에서 이미 보장됨 — 이 테스트는 변경하지 않는다.
 * 006 AdminMemberWiringTest 패턴 계승.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class MemberSignupWiringTest {

    @Autowired
    private ApplicationContext context;

    @MockBean
    MemberRepository memberRepository;

    @MockBean
    MemberUserDetailsService memberUserDetailsService;

    @MockBean
    CategoryRepository categoryRepository;

    @MockBean
    ProductRepository productRepository;

    @Test
    @DisplayName("운영 배선: MemberRestController 빈이 컨텍스트에 등록된다")
    void memberRestController_bean_is_registered() {
        MemberRestController bean = context.getBean(MemberRestController.class);
        assertThat(bean).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: MemberServiceResponse 빈이 컨텍스트에 등록된다")
    void memberServiceResponse_bean_is_registered() {
        MemberServiceResponse bean = context.getBean(MemberServiceResponse.class);
        assertThat(bean).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: MemberSignupViewController 빈이 컨텍스트에 등록된다")
    void memberSignupViewController_bean_is_registered() {
        MemberSignupViewController bean = context.getBean(MemberSignupViewController.class);
        assertThat(bean).isNotNull();
    }
}
