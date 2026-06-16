package com.shop.shop.common.storage;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.web.StaticResourceConfig;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObjectStorage 빈 배선/토글 테스트.
 *
 * <p>type=r2 / type 미지정(기본=local) 두 컨텍스트에서 올바른 빈이 등록되고
 * StaticResourceConfig가 상호배타적으로 등록/미등록되는지 검증한다.
 *
 * <p>plan §5.3 + testing-rule §조건부 빈 true 경로 + 운영 배선 검증.
 */
class ObjectStorageWiringTest {

    /**
     * type=r2: R2ObjectStorage + S3Client 등록, StaticResourceConfig 미등록.
     *
     * <p>NPE 회피: endpoint=null이면 R2StorageConfig.s3Client 빈 생성 단계 NPE 발생.
     * {@code @TestPropertySource}로 더미 endpoint/access-key/secret-key를 반드시 주입한다.
     * S3Client는 실 연결 없이 빈 생성만 수행한다.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "shop.storage.type=r2",
            "shop.storage.r2.endpoint=http://localhost:1",
            "shop.storage.r2.bucket=test-bucket",
            "shop.storage.r2.access-key=dummy-access-key",
            "shop.storage.r2.secret-key=dummy-secret-key"
    })
    @Import(FakeRefreshTokenStore.class)
    @MockSharedRepositories
    @DisplayName("type=r2 컨텍스트")
    class WhenTypeIsR2 {

        @Autowired
        ApplicationContext context;

        @MockitoBean MemberRepository memberRepository;
        @MockitoBean SellerApplicationRepository sellerApplicationRepository;
        @MockitoBean MemberUserDetailsService memberUserDetailsService;
        @MockitoBean CategoryRepository categoryRepository;
        @MockitoBean ProductRepository productRepository;
        @MockitoBean ProductOptionRepository productOptionRepository;
        @MockitoBean OptionValueRepository optionValueRepository;
        @MockitoBean ProductVariantRepository productVariantRepository;
        @MockitoBean ProductImageRepository productImageRepository;
        @MockitoBean CartRepository cartRepository;
        @MockitoBean CartItemRepository cartItemRepository;
        @MockitoBean InventoryStockRepository inventoryStockRepository;
        @MockitoBean OrderRepository orderRepository;
        @MockitoBean ShipmentRepository shipmentRepository;
        @MockitoBean PaymentRepository paymentRepository;
        @MockitoBean CouponRepository couponRepository;
        @MockitoBean UserCouponRepository userCouponRepository;
        @MockitoBean OrderItemQueryRepository orderItemQueryRepository;
        @MockitoBean ReviewRepository reviewRepository;

        @Test
        @DisplayName("ObjectStorage 빈이 R2ObjectStorage 인스턴스임")
        void objectStorage_isR2ObjectStorage() {
            ObjectStorage objectStorage = context.getBean(ObjectStorage.class);
            assertThat(objectStorage).isInstanceOf(R2ObjectStorage.class);
        }

        @Test
        @DisplayName("S3Client 빈이 존재함")
        void s3Client_beanExists() {
            assertThat(context.getBeansOfType(S3Client.class)).isNotEmpty();
        }

        @Test
        @DisplayName("StaticResourceConfig 빈이 존재하지 않음 (/** 가로챔 차단)")
        void staticResourceConfig_beanAbsent() {
            assertThat(context.getBeanNamesForType(StaticResourceConfig.class)).isEmpty();
        }
    }

    /**
     * type 미지정(기본=local): LocalObjectStorage 등록, StaticResourceConfig 등록.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @Import(FakeRefreshTokenStore.class)
    @MockSharedRepositories
    @DisplayName("type 미지정(기본 local) 컨텍스트")
    class WhenTypeIsDefault {

        @Autowired
        ApplicationContext context;

        @MockitoBean MemberRepository memberRepository;
        @MockitoBean SellerApplicationRepository sellerApplicationRepository;
        @MockitoBean MemberUserDetailsService memberUserDetailsService;
        @MockitoBean CategoryRepository categoryRepository;
        @MockitoBean ProductRepository productRepository;
        @MockitoBean ProductOptionRepository productOptionRepository;
        @MockitoBean OptionValueRepository optionValueRepository;
        @MockitoBean ProductVariantRepository productVariantRepository;
        @MockitoBean ProductImageRepository productImageRepository;
        @MockitoBean CartRepository cartRepository;
        @MockitoBean CartItemRepository cartItemRepository;
        @MockitoBean InventoryStockRepository inventoryStockRepository;
        @MockitoBean OrderRepository orderRepository;
        @MockitoBean ShipmentRepository shipmentRepository;
        @MockitoBean PaymentRepository paymentRepository;
        @MockitoBean CouponRepository couponRepository;
        @MockitoBean UserCouponRepository userCouponRepository;
        @MockitoBean OrderItemQueryRepository orderItemQueryRepository;
        @MockitoBean ReviewRepository reviewRepository;

        @Test
        @DisplayName("ObjectStorage 빈이 LocalObjectStorage 인스턴스임")
        void objectStorage_isLocalObjectStorage() {
            ObjectStorage objectStorage = context.getBean(ObjectStorage.class);
            assertThat(objectStorage).isInstanceOf(LocalObjectStorage.class);
        }

        @Test
        @DisplayName("S3Client 빈이 존재하지 않음")
        void s3Client_beanAbsent() {
            assertThat(context.getBeanNamesForType(S3Client.class)).isEmpty();
        }

        @Test
        @DisplayName("StaticResourceConfig 빈이 존재함 (로컬 파일 서빙 활성)")
        void staticResourceConfig_beanExists() {
            assertThat(context.getBeanNamesForType(StaticResourceConfig.class)).isNotEmpty();
        }
    }
}
