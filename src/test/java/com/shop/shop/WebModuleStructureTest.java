package com.shop.shop;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * web 모듈 아키텍처 구조 검증 테스트 (ArchUnit).
 *
 * <p>plain JUnit 5 — @SpringBootTest 불필요. ArchUnit 정적 바이트코드 분석이므로 실 DB/컨텍스트 부팅 없음.
 *
 * <p>검증 규칙:
 * <ol>
 *   <li>web 클래스가 member/product 내부 패키지(domain/repository/service)를 직접 참조하지 않음.
 *       web은 named interface(spi/dto)만 의존해야 한다.</li>
 *   <li>도메인 클래스(member/product 등)가 web을 참조하지 않음 (단방향 의존).
 *       도메인 모듈은 web을 알지 못해야 한다.</li>
 *   <li>web이 도메인 Entity(..domain..)·Repository(..repository..)를 직접 참조하지 않음
 *       (규칙 1 강화 — task 검증 항목 명시적 선언).</li>
 * </ol>
 *
 * <p>web 패키지({@code com.shop.shop.web})에 ViewController 5종이 실재하므로 세 규칙 모두
 * 실질적으로 위반을 검출한다. 규칙 1·3은 web 클래스의 도메인 내부/Entity/Repository 참조를,
 * 규칙 2는 도메인 → web 역참조를 차단한다.
 */
class WebModuleStructureTest {

    private static JavaClasses shopClasses;

    @BeforeAll
    static void importClasses() {
        shopClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.shop.shop");
    }

    /**
     * 규칙 1: web 클래스는 member/product 내부 패키지(domain/repository/service)를 직접 참조하지 않는다.
     *
     * <p>web은 named interface(spi/dto)를 통해서만 도메인에 접근해야 한다.
     * Entity·Repository·비공개 Service는 web의 컴파일 의존 대상이 아니다.
     */
    @Test
    @DisplayName("규칙 1: web 클래스가 member/product 내부 패키지(domain·repository·service)를 직접 참조하지 않음")
    void web_does_not_depend_on_domain_internals() {
        // com.shop.shop.web 패키지를 정확히 지정 (org.springframework.web 오매칭 방지)
        // allowEmptyShould(true): 매칭 클래스 0개여도 통과 허용(향후 web 패키지 제거 대비 방어)
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.web..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.member.domain..",
                        "com.shop.shop.member.repository..",
                        "com.shop.shop.member.service..",
                        "com.shop.shop.product.domain..",
                        "com.shop.shop.product.repository..",
                        "com.shop.shop.product.service.."
                )
                .because("web 모듈은 도메인 내부(domain/repository/service)를 직접 참조하지 않고 " +
                         "named interface(spi/dto)만 의존해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }

    /**
     * 규칙 2: 도메인 클래스(member/product 등)는 web을 참조하지 않는다.
     *
     * <p>의존 방향은 web → {domain}.spi/.dto 단방향이어야 한다.
     * 도메인 모듈이 web을 역참조하면 도메인 자율성이 훼손된다.
     *
     * <p>이 규칙은 web 패키지가 아직 없어도 "web 참조 금지"로서 이미 유효하다.
     */
    @Test
    @DisplayName("규칙 2: 도메인 클래스(member·product 등)가 web을 참조하지 않음 (단방향 의존 보장)")
    void domain_does_not_depend_on_web() {
        // com.shop.shop.web 패키지를 정확히 지정 (org.springframework.web 오매칭 방지)
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.shop.shop.member..",
                        "com.shop.shop.product..",
                        "com.shop.shop.cart..",
                        "com.shop.shop.order..",
                        "com.shop.shop.payment..",
                        "com.shop.shop.inventory.."
                )
                .should().dependOnClassesThat()
                .resideInAPackage("com.shop.shop.web..")
                .because("도메인 모듈은 web 모듈을 참조하지 않아야 한다. " +
                         "의존 방향은 web → domain(spi/dto) 단방향이다.");

        rule.check(shopClasses);
    }

    /**
     * 규칙 3: web 클래스는 도메인 Entity(..domain..)·Repository(..repository..)를 직접 참조하지 않는다.
     *
     * <p>규칙 1의 일부를 task 검증 항목 기준으로 명시적으로 선언한 규칙.
     * Entity를 모델에 직접 담지 않고, Repository를 web 레이어가 직접 호출하지 않는다.
     */
    @Test
    @DisplayName("규칙 3: web 클래스가 도메인 Entity(..domain..)·Repository(..repository..)를 직접 참조하지 않음")
    void web_does_not_directly_use_entities_or_repositories() {
        // com.shop.shop.web 패키지를 정확히 지정 (org.springframework.web 오매칭 방지)
        // allowEmptyShould(true): 매칭 클래스 0개여도 통과 허용(향후 web 패키지 제거 대비 방어)
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.shop.shop.web..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.shop.shop.member.domain..",
                        "com.shop.shop.product.domain..",
                        "com.shop.shop.cart.domain..",
                        "com.shop.shop.order.domain..",
                        "com.shop.shop.payment.domain..",
                        "com.shop.shop.inventory.domain..",
                        "com.shop.shop.member.repository..",
                        "com.shop.shop.product.repository..",
                        "com.shop.shop.cart.repository..",
                        "com.shop.shop.order.repository..",
                        "com.shop.shop.payment.repository..",
                        "com.shop.shop.inventory.repository.."
                )
                .because("web 모듈은 Entity·Repository를 직접 참조하지 않고 " +
                         "facade(spi)가 반환하는 DTO만 사용해야 한다.")
                .allowEmptyShould(true);

        rule.check(shopClasses);
    }
}
