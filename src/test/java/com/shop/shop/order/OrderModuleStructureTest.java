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
}
