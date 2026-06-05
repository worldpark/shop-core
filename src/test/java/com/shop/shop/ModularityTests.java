package com.shop.shop;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith 모듈 구조 정적 검증 테스트.
 *
 * <p>plain JUnit 5 — @SpringBootTest 불필요. ArchUnit 기반 정적 분석이므로 실 DB/컨텍스트 부팅 없음.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>모듈 간 순환 의존(cycle) 부재</li>
 *   <li>서브패키지(internal) 타입을 외부 모듈이 직접 참조하지 않음</li>
 *   <li>OPEN 모듈(common)의 internal 참조는 허용</li>
 * </ul>
 *
 * <p>인식되는 모듈: member / product / cart / order / payment / inventory /
 * common(OPEN) / security / web / platform
 *
 * <p>web — Thymeleaf ViewController·ViewModel·Form·화면 조립 전담 지원 모듈(Task 003).
 * member.spi, member.dto, product.spi, product.dto named interface만 의존한다.
 * 기존 home 모듈은 web으로 통합되어 제거되었다(HomeViewController → web.home).
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(ShopCoreApplication.class);

    @Test
    void verifiesModuleStructure() {
        MODULES.verify();
    }
}
