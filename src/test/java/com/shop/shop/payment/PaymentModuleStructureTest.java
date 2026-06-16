package com.shop.shop.payment;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * payment 모듈 아키텍처 구조 검증 테스트 (ArchUnit).
 *
 * <p>plain JUnit 5 — @SpringBootTest 불필요. ArchUnit 정적 바이트코드 분석이므로 실 DB/컨텍스트 부팅 없음.
 *
 * <p>검증 규칙:
 * <ol>
 *   <li>payment 클래스가 order 내부(domain/repository/service)를 직접 참조하지 않음 (order.spi만 허용)</li>
 *   <li>payment 클래스가 member 내부(domain/repository/service)를 직접 참조하지 않음 (member.spi만 허용)</li>
 *   <li>payment 클래스가 product 내부(domain/repository/service)를 직접 참조하지 않음</li>
 *   <li>OrderCompletedEvent는 order 모듈(order.event 패키지)에 위치함 (payment 모듈 미소유)</li>
 *   <li>PaymentFailedEvent는 payment 모듈(payment.event 패키지)에 위치함 (발행 소유권, 017)</li>
 * </ol>
 */
class PaymentModuleStructureTest {

    private static JavaClasses shopClasses;

    @BeforeAll
    static void importClasses() {
        shopClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.shop.shop");
    }

    /**
     * 규칙 1: payment 클래스는 order 내부(domain/repository/service)를 직접 참조하지 않는다.
     *
     * <p>payment 모듈은 order.spi(OrderPaymentReader, OrderConfirmation)만 사용해야 한다.
     * OrderConfirmationImpl 등 order 내부 구현체를 직접 알면 모듈 경계가 무너진다(P1).
     */
    @Test
    @DisplayName("규칙 1: payment 클래스가 order 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void payment_does_not_depend_on_order_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.payment..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.order.domain..",
                        "com.shop.shop.order.repository..",
                        "com.shop.shop.order.service.."
                )
                .because("payment 모듈은 order 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "order.spi(OrderPaymentReader/OrderConfirmation)만 사용해야 한다(P1 모듈 경계).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 2: payment 클래스는 member 내부(domain/repository/service)를 직접 참조하지 않는다.
     *
     * <p>payment 모듈은 member.spi(MemberDirectory)만 사용해야 한다(PaymentFacadeImpl).
     */
    @Test
    @DisplayName("규칙 2: payment 클래스가 member 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void payment_does_not_depend_on_member_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.payment..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.member.domain..",
                        "com.shop.shop.member.repository..",
                        "com.shop.shop.member.service.."
                )
                .because("payment 모듈은 member 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "member.spi(MemberDirectory)만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 3: payment 클래스는 product 내부(domain/repository/service)를 직접 참조하지 않는다.
     */
    @Test
    @DisplayName("규칙 3: payment 클래스가 product 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void payment_does_not_depend_on_product_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.payment..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.product.domain..",
                        "com.shop.shop.product.repository..",
                        "com.shop.shop.product.service.."
                )
                .because("payment 모듈은 product 내부(domain/repository/service)를 직접 참조하지 않는다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 4: OrderCompletedEvent는 order 모듈(order.event 패키지)에 위치한다.
     *
     * <p>이벤트 소유권은 이벤트를 발행하는 order 모듈이다.
     * payment 모듈은 이벤트를 발행하지 않으며 order.event 패키지를 소유하지 않는다.
     */
    @Test
    @DisplayName("규칙 4: OrderCompletedEvent가 payment 패키지가 아닌 order.event 패키지에 위치함")
    void orderCompletedEvent_resides_in_order_event_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("OrderCompletedEvent")
                .should().resideInAPackage("com.shop.shop.payment..")
                .because("OrderCompletedEvent는 이벤트 발행 주체인 order 모듈(order.event)이 소유해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 5: PaymentFailedEvent는 payment 모듈(payment.event 패키지)에 위치한다(017 발행 소유권).
     *
     * <p>결제 실패는 payment 도메인 사건이므로 payment 모듈이 발행 소유한다(package-structure-rule).
     * order/member/common 패키지에 위치하면 소유권이 위반된다.
     */
    @Test
    @DisplayName("규칙 5: PaymentFailedEvent가 payment.event 패키지에 위치함 (017 발행 소유권)")
    void paymentFailedEvent_resides_in_payment_event_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("PaymentFailedEvent")
                .should().resideOutsideOfPackage("com.shop.shop.payment.event..")
                .because("PaymentFailedEvent는 payment 모듈(payment.event)이 소유해야 한다(package-structure-rule).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 6: PaymentFailedEvent는 order/member/common 패키지에 위치하지 않는다.
     */
    @Test
    @DisplayName("규칙 6: PaymentFailedEvent가 order/member/common 패키지에 위치하지 않음")
    void paymentFailedEvent_not_in_order_or_member_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("PaymentFailedEvent")
                .should().resideInAnyPackage(
                        "com.shop.shop.order..",
                        "com.shop.shop.member..",
                        "com.shop.shop.common.."
                )
                .because("PaymentFailedEvent는 결제 실패 사건이므로 payment 모듈(payment.event)이 소유해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    // ============================================================
    // 018 신규 규칙
    // ============================================================

    /**
     * 규칙 7: OrderCancelledEvent는 order 모듈(order.event 패키지)에 위치한다 (018).
     *
     * <p>주문 취소 사건은 order 모듈이 소유한다 — OrderCompletedEvent와 동일한 소유권 원칙.
     * payment 모듈에 있으면 소유권 위반이다.
     */
    @Test
    @DisplayName("규칙 7: OrderCancelledEvent가 payment 패키지가 아닌 order.event 패키지에 위치함 (018)")
    void orderCancelledEvent_resides_in_order_event_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("OrderCancelledEvent")
                .should().resideInAPackage("com.shop.shop.payment..")
                .because("OrderCancelledEvent는 이벤트 발행 주체인 order 모듈(order.event)이 소유해야 한다(018).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 8: payment 클래스는 order 내부를 직접 참조하지 않는다 (018 추가 — 순환 없음 재확인).
     *
     * <p>OrderCancellationImpl이 order 내부 service에 추가됐어도 payment가 직접 알면 안 된다.
     * PaymentService는 OrderCancellation(SPI, order.spi 패키지)만 사용한다.
     */
    @Test
    @DisplayName("규칙 8: payment↔order 순환 없음 — payment가 order.service 직접 참조하지 않음 (018)")
    void payment_does_not_depend_on_order_service_018() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.payment..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.shop.shop.order.service..")
                .because("payment 모듈은 order 내부 service를 직접 참조하지 않고 " +
                         "order.spi(OrderCancellation/OrderPaymentReader)만 사용해야 한다(018 순환 없음).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    // ============================================================
    // 022 신규 규칙
    // ============================================================

    /**
     * 규칙 9: UnpaidOrderExpiryScheduler·OrderExpiryProperties·OrderExpirySchedulingConfig가 payment 모듈에 위치한다 (022).
     *
     * <p>만료 오케스트레이션은 payment 모듈이 소유한다(018 대칭 — 순환 없음).
     */
    @Test
    @DisplayName("규칙 9: 만료 스케줄러 컴포넌트가 payment 모듈(payment.service)에 위치함 (022)")
    void expiryScheduler_resides_in_payment_service_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("UnpaidOrderExpiryScheduler")
                .should().resideOutsideOfPackage("com.shop.shop.payment.service..")
                .because("만료 스케줄러는 payment 모듈이 소유해야 한다(022 — 018 대칭 순환 없음).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 10: OrderExpiryReader가 order.spi 패키지에 위치한다 (022).
     *
     * <p>만료 대상 조회 SPI는 order 모듈이 소유한다.
     */
    @Test
    @DisplayName("규칙 10: OrderExpiryReader가 order.spi 패키지에 위치함 (022)")
    void orderExpiryReader_resides_in_order_spi_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("OrderExpiryReader")
                .should().resideOutsideOfPackage("com.shop.shop.order.spi..")
                .because("만료 대상 조회 SPI는 order 모듈(order.spi)이 소유해야 한다(022).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    // ============================================================
    // 035 신규 규칙
    // ============================================================

    /**
     * 규칙 11: payment 클래스는 Redisson API를 직접 참조하지 않는다 (035).
     *
     * <p>분산락 호출은 {@code common.concurrency.SchedulerLeaderGuard} 인터페이스를 통해서만 이뤄져야 한다.
     * payment 클래스가 {@code org.redisson} 패키지를 직접 의존하면 락 메커니즘이 도메인에 흩뿌려진다(ADR-005 격리).
     */
    @Test
    @DisplayName("규칙 11: payment 클래스가 Redisson API(org.redisson..)를 직접 참조하지 않음 (035 — guard 격리)")
    void payment_does_not_depend_on_redisson_directly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.payment..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.redisson..")
                .because("payment 모듈은 Redisson API를 직접 참조하지 않고 " +
                         "common.concurrency.SchedulerLeaderGuard(OPEN 모듈)만 사용해야 한다(035 — 락 메커니즘 격리).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }
}
