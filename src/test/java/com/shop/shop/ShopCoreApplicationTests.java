package com.shop.shop;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 애플리케이션 컨텍스트 로드 테스트.
 *
 * <p>test 프로파일: DataSource/JPA/Kafka/Flyway/Redis 자동설정 제외 (test application.yml).
 * - MemberRepository: @MockitoBean (JPA Repository — DataSource 없는 컨텍스트에서 실 빈 생성 불가)
 * - MemberUserDetailsService: 의존 관계상 MemberRepository mock이 있으면 실 빈으로 생성 가능.
 *   그러나 DataSource 제외 환경에서 JPA transaction 관련 빈이 부재할 수 있으므로 MockitoBean 사용.
 * - CategoryRepository/ProductRepository: @MockitoBean (JPA Repository — DataSource 없이 실 빈 생성 불가)
 * - RefreshTokenStore: FakeRefreshTokenStore (@Import + @Primary) — Redis 미기동 비파괴.
 * - JwtProperties: test application.yml의 더미 secret으로 JwtProperties 검증 통과.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class ShopCoreApplicationTests {

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
    void contextLoads() {
    }
}
