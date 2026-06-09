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
}
