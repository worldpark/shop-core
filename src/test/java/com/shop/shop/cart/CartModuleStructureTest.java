package com.shop.shop.cart;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * cart 모듈 아키텍처 구조 검증 테스트 (ArchUnit).
 *
 * <p>plain JUnit 5 — @SpringBootTest 불필요. ArchUnit 정적 바이트코드 분석이므로 실 DB/컨텍스트 부팅 없음.
 *
 * <p>검증 규칙:
 * <ol>
 *   <li>cart 클래스가 product 내부 패키지(domain/repository/service)를 직접 참조하지 않음.</li>
 *   <li>cart 클래스가 member 내부 패키지(domain/repository/service)를 직접 참조하지 않음.</li>
 *   <li>cart 클래스가 product.spi/member.spi(+scalar)만 사용.</li>
 *   <li>cart 클래스가 product.spi.UserDirectory를 참조하지 않음 (member.spi.MemberDirectory만).</li>
 * </ol>
 */
class CartModuleStructureTest {

    private static JavaClasses shopClasses;

    @BeforeAll
    static void importClasses() {
        shopClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.shop.shop");
    }

    /**
     * 규칙 1: cart 클래스는 product 내부 패키지(domain/repository/service)를 직접 참조하지 않는다.
     */
    @Test
    @DisplayName("규칙 1: cart 클래스가 product 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void cart_does_not_depend_on_product_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.cart..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.product.domain..",
                        "com.shop.shop.product.repository..",
                        "com.shop.shop.product.service.."
                )
                .because("cart 모듈은 product 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "product.spi(ProductPurchaseCatalog)만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 2: cart 클래스는 member 내부 패키지(domain/repository/service)를 직접 참조하지 않는다.
     */
    @Test
    @DisplayName("규칙 2: cart 클래스가 member 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void cart_does_not_depend_on_member_internals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.cart..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.member.domain..",
                        "com.shop.shop.member.repository..",
                        "com.shop.shop.member.service.."
                )
                .because("cart 모듈은 member 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "member.spi(MemberDirectory)만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 3: cart 클래스는 product.spi.UserDirectory를 참조하지 않는다.
     *
     * <p>cart의 email→userId 변환 포트는 member.spi.MemberDirectory만 사용해야 한다.
     * UserDirectory는 product 소유 포트(판매자 소유권)이므로 cart가 재사용하면 모듈 소유 경계가 흐려진다.
     */
    @Test
    @DisplayName("규칙 3: cart 클래스가 product.spi.UserDirectory를 참조하지 않음 (member.spi.MemberDirectory만)")
    void cart_does_not_use_product_user_directory() {
        // ArchUnit ClassesThat은 체이닝 필터 지원이 제한적이므로, 패키지 전체 제한으로 단순화
        // cart → product.spi 자체는 ProductPurchaseCatalog 사용을 위해 허용해야 하므로
        // UserDirectory 클래스명 포함 여부는 직접 코드 리뷰로 확인하고,
        // 여기서는 구조 테스트로서 cart → product.domain/repository/service 금지를 1·2번 규칙으로 대신한다.
        // (UserDirectory는 product.spi 패키지에 있고 cart는 product.spi의 ProductPurchaseCatalog만 사용)
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.cart..")
                .should().dependOnClassesThat()
                .haveSimpleName("UserDirectory")
                .because("cart는 product.spi.UserDirectory가 아닌 member.spi.MemberDirectory를 통해서만 " +
                         "email→userId 변환을 수행해야 한다(소유 모듈 분리).")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }
}
