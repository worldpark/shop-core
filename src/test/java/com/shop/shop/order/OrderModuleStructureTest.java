package com.shop.shop.order;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * order 모듈 아키텍처 구조 검증 테스트 (ArchUnit).
 *
 * <p>plain JUnit 5 — @SpringBootTest 불필요. ArchUnit 정적 바이트코드 분석이므로 실 DB/컨텍스트 부팅 없음.
 *
 * <p>검증 규칙:
 * <ol>
 *   <li>order 클래스가 cart 내부(domain/repository/service)를 직접 참조하지 않음 (cart.spi만 허용)</li>
 *   <li>order 클래스가 product 내부(domain/repository/service)를 직접 참조하지 않음 (product.spi만 허용)</li>
 *   <li>order 클래스가 inventory 내부(domain/repository/service)를 직접 참조하지 않음 (inventory.spi만 허용)</li>
 *   <li>order 클래스가 member 내부(domain/repository/service)를 직접 참조하지 않음 (member.spi만 허용)</li>
 *   <li>inventory 클래스가 product 내부(domain/repository/service)를 직접 참조하지 않음</li>
 * </ol>
 */
class OrderModuleStructureTest {

    private static JavaClasses shopClasses;

    @BeforeAll
    static void importClasses() {
        shopClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.shop.shop");
    }

    /**
     * 규칙 1: order 클래스는 cart 내부(domain/repository/service)를 직접 참조하지 않는다.
     */
    @Test
    @DisplayName("규칙 1: order 클래스가 cart 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void order_does_not_depend_on_cart_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.order..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.cart.domain..",
                        "com.shop.shop.cart.repository..",
                        "com.shop.shop.cart.service.."
                )
                .because("order 모듈은 cart 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "cart.spi(CartCheckoutReader)만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 2: order 클래스는 product 내부(domain/repository/service)를 직접 참조하지 않는다.
     */
    @Test
    @DisplayName("규칙 2: order 클래스가 product 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void order_does_not_depend_on_product_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.order..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.product.domain..",
                        "com.shop.shop.product.repository..",
                        "com.shop.shop.product.service.."
                )
                .because("order 모듈은 product 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "product.spi(ProductOrderCatalog)만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 3: order 클래스는 inventory 내부(domain/repository/service)를 직접 참조하지 않는다.
     */
    @Test
    @DisplayName("규칙 3: order 클래스가 inventory 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void order_does_not_depend_on_inventory_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.order..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.inventory.domain..",
                        "com.shop.shop.inventory.repository..",
                        "com.shop.shop.inventory.service.."
                )
                .because("order 모듈은 inventory 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "inventory.spi(InventoryStockPort)만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 4: order 클래스는 member 내부(domain/repository/service)를 직접 참조하지 않는다.
     */
    @Test
    @DisplayName("규칙 4: order 클래스가 member 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void order_does_not_depend_on_member_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.order..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.member.domain..",
                        "com.shop.shop.member.repository..",
                        "com.shop.shop.member.service.."
                )
                .because("order 모듈은 member 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "member.spi(MemberDirectory)만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 5: inventory 클래스는 product 내부(domain/repository/service)를 직접 참조하지 않는다.
     *
     * <p>VariantStock이 product_variants 테이블을 매핑하지만 product Entity/Repository/Service를
     * 직접 참조해서는 안 된다. 재고 write 주체는 VariantStock 하나로 한정된다.
     */
    @Test
    @DisplayName("규칙 5: inventory 클래스가 product 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void inventory_does_not_depend_on_product_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.inventory..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.product.domain..",
                        "com.shop.shop.product.repository..",
                        "com.shop.shop.product.service.."
                )
                .because("inventory 모듈은 product 내부(domain/repository/service)를 직접 참조하지 않는다. " +
                         "재고 차감은 inventory 소유 VariantStock으로만 수행한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    // ============================================================
    // 018 신규 규칙
    // ============================================================

    /**
     * 규칙 6: OrderCancelledEvent는 order.event 패키지에 위치한다 (018).
     *
     * <p>주문 취소 사건의 이벤트 소유권은 order 모듈이다.
     * OrderCompletedEvent와 동일한 원칙: payment 모듈에 있으면 소유권 위반.
     */
    @Test
    @DisplayName("규칙 6: OrderCancelledEvent가 order.event 패키지에 위치함 (018)")
    void orderCancelledEvent_resides_in_order_event_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("OrderCancelledEvent")
                .should().resideOutsideOfPackage("com.shop.shop.order.event..")
                .because("OrderCancelledEvent는 이벤트 발행 주체인 order 모듈(order.event)이 소유해야 한다(018).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 7: order 클래스는 payment 내부(domain/repository/service)를 직접 참조하지 않는다 (018 순환 없음).
     *
     * <p>018에서 OrderCancellationImpl이 order.service에 추가됐다.
     * payment 오케스트레이션 방향(payment → order.spi)을 유지해야 하므로
     * order가 payment 내부를 역방향 참조하면 순환이 된다.
     */
    @Test
    @DisplayName("규칙 7: order 클래스가 payment 내부(domain·repository·service)를 직접 참조하지 않음 (018)")
    void order_does_not_depend_on_payment_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.order..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.payment.domain..",
                        "com.shop.shop.payment.repository..",
                        "com.shop.shop.payment.service.."
                )
                .because("order 모듈은 payment 내부(domain/repository/service)를 직접 참조하지 않는다. " +
                         "payment가 order.spi를 호출하는 단방향 의존성을 유지해야 한다(018 순환 없음).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    // ============================================================
    // 019 신규 규칙
    // ============================================================

    /**
     * 규칙 8: Shipment/ShipmentItem 엔티티가 order.domain 패키지에 위치한다 (019).
     *
     * <p>배송은 order 모듈의 책임 — architecture.md §5.
     */
    @Test
    @DisplayName("규칙 8: Shipment/ShipmentItem이 order.domain 패키지에 위치함 (019)")
    void shipmentEntities_reside_in_order_domain() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("Shipment").or().haveSimpleName("ShipmentItem")
                .should().resideOutsideOfPackage("com.shop.shop.order.domain..")
                .because("Shipment/ShipmentItem은 배송이 order 모듈 책임이므로 order.domain에 위치해야 한다(019).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 9: OrderFulfillmentService가 payment.spi/payment 패키지를 의존하지 않는다 (019).
     *
     * <p>배송 생성은 결제(payment) 모듈과 무관하다. 새 cross-module 의존 0.
     */
    @Test
    @DisplayName("규칙 9: OrderFulfillmentService가 payment.spi/payment 패키지를 의존하지 않음 (019)")
    void orderFulfillmentService_does_not_depend_on_payment() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("OrderFulfillmentService")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.payment..",
                        "com.shop.shop.payment.spi.."
                )
                .because("OrderFulfillmentService는 payment 모듈을 의존하지 않는다(019 배송 생성은 외부계 없음).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 10: OrderFulfillmentService가 inventory 패키지를 의존하지 않는다 (019).
     *
     * <p>배송 생성은 재고(inventory)와 무관하다. 재고는 015 차감/018 복원 소관.
     */
    @Test
    @DisplayName("규칙 10: OrderFulfillmentService가 inventory 패키지를 의존하지 않음 (019)")
    void orderFulfillmentService_does_not_depend_on_inventory() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("OrderFulfillmentService")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.inventory..",
                        "com.shop.shop.inventory.spi.."
                )
                .because("OrderFulfillmentService는 inventory 모듈을 의존하지 않는다(019 배송 생성은 재고 무관).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    // ============================================================
    // 020 신규 규칙
    // ============================================================

    /**
     * 규칙 11: ShippingStartedEvent가 order.event 패키지에 위치한다 (020).
     *
     * <p>배송 시작 이벤트의 소유권은 order 모듈이다.
     * OrderCompletedEvent/OrderCancelledEvent와 동일한 원칙.
     */
    @Test
    @DisplayName("규칙 11: ShippingStartedEvent가 order.event 패키지에 위치함 (020)")
    void shippingStartedEvent_resides_in_order_event_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("ShippingStartedEvent")
                .should().resideOutsideOfPackage("com.shop.shop.order.event..")
                .because("ShippingStartedEvent는 이벤트 발행 주체인 order 모듈(order.event)이 소유해야 한다(020).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    // ============================================================
    // 022 신규 규칙
    // ============================================================

    /**
     * 규칙 12: OrderExpiryReader·OrderExpiryReaderImpl이 order.spi/order.service 패키지에 위치한다 (022).
     *
     * <p>만료 대상 조회 SPI와 구현체는 order 모듈이 소유한다.
     */
    @Test
    @DisplayName("규칙 12: OrderExpiryReader가 order.spi 패키지에 위치함 (022)")
    void orderExpiryReader_resides_in_order_spi_package() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("OrderExpiryReader")
                .should().resideOutsideOfPackage("com.shop.shop.order.spi..")
                .because("만료 대상 조회 SPI는 order 모듈(order.spi)이 소유해야 한다(022).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 13: order 클래스는 payment.service를 직접 참조하지 않는다 (022 순환 없음).
     *
     * <p>만료 스케줄러/오케스트레이션은 payment 모듈이 소유 — order가 payment.service를 역방향 참조하면 순환.
     */
    @Test
    @DisplayName("규칙 13: order 클래스가 payment.service를 직접 참조하지 않음 (022 순환 없음)")
    void order_does_not_depend_on_payment_service_022() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.order..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.shop.shop.payment.service..")
                .because("order 모듈은 payment.service를 직접 참조하지 않는다(022 — payment→order.spi 단방향 유지).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }
}
