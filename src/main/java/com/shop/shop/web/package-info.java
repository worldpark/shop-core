/**
 * web — Thymeleaf 화면 조립 지원 모듈.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>Thymeleaf ViewController(@Controller) 보유: HomeViewController, LoginViewController,
 *       MemberSignupViewController, AdminMemberViewController, SellerProductViewController,
 *       SellerProductVariantViewController</li>
 *   <li>View ViewModel · Form · 화면 조립 전담</li>
 *   <li>Spring Security Authentication을 View facade 호출용 actor 컨텍스트로 변환하는
 *       support 컴포넌트 보유</li>
 *   <li>도메인의 named interface(spi/dto)만 의존 — 도메인 내부(Entity·Repository·비공개 Service) 직접 참조 금지</li>
 * </ul>
 *
 * <h2>허용 의존</h2>
 * <ul>
 *   <li>{@code member.spi} (named interface) — {@code MemberSignupFacade}, {@code AdminMemberFacade}</li>
 *   <li>{@code member.dto} (named interface) — {@code SignupForm}, {@code MemberSearchCondition},
 *       {@code MemberSummaryResponse}</li>
 *   <li>{@code product.spi} (named interface) — {@code SellerProductFacade}, {@code SellerProductVariantFacade}</li>
 *   <li>{@code product.dto} (named interface) — {@code ProductForm}, {@code CategoryResponse},
 *       {@code ProductFormView}, {@code VariantManagementView}, {@code SellerProductRef},
 *       {@code ProductOptionResponse}, {@code OptionValueResponse}, {@code ProductVariantResponse}</li>
 *   <li>{@code common} (OPEN 모듈) — {@code DuplicateEmailException}, {@code BusinessException}</li>
 *   <li>Spring MVC / Spring Security / Thymeleaf 프레임워크 타입</li>
 * </ul>
 *
 * <h2>web support 경계</h2>
 * <p>{@code web.support}는 View 진입점 보조만 담당한다. 허용 예:
 * {@code Authentication -> CurrentActor(email, admin)} 변환, 화면 모델 조립 보조.
 * 금지 예: 도메인 소유권 검사, 트랜잭션, Repository 접근, Entity 접근, 상품/회원 상태 기반 비즈니스 판단.
 * 그런 규칙은 도메인 facade/service에 둔다.
 *
 * <h2>금지 의존</h2>
 * <ul>
 *   <li>도메인 Entity ({@code member.domain}, {@code product.domain}, …)</li>
 *   <li>Repository ({@code member.repository}, {@code product.repository}, …)</li>
 *   <li>비공개 Service ({@code member.service}, {@code product.service}, …)</li>
 *   <li>도메인 enum — {@code Role}, {@code ProductStatus} 직접 참조 금지
 *       (facade·dto 경계에서 String으로 수신)</li>
 * </ul>
 *
 * <h2>home 모듈 통합 결정</h2>
 * <p>Task 003(2026-06) 결정: {@code home} 모듈(컨트롤러 1개짜리 화면 진입 모듈)을 이 {@code web} 모듈로
 * 통합하고 {@code com.shop.shop.home} 패키지를 제거한다.
 * 홈 화면 진입점({@code HomeViewController})이 다른 화면 컨트롤러와 동일한 화면 조립 책임을 지므로
 * 분리 유지 이유가 없다. 결정 근거는 {@code docs/rules/package-structure-rule.md}에도 명시됨.
 *
 * <h2>의존 방향</h2>
 * <p>도메인 모듈은 이 {@code web} 모듈을 참조하지 않는다 (단방향: web → domain.spi/.dto).
 * 따라서 외부 모듈이 web을 의존할 이유가 없으며 named interface는 불필요하다.
 */
@org.springframework.modulith.ApplicationModule
package com.shop.shop.web;
