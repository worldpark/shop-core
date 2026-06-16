package com.shop.shop.product.spi;

import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.ProductFormView;
import com.shop.shop.product.dto.SellerProductStatsData;
import com.shop.shop.product.dto.SellerProductSummaryView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 판매자 상품 관리 View 전용 facade (published port).
 *
 * <p>web 모듈의 SellerProductViewController가 product 도메인 내부 Service·Entity·enum을 직접
 * 참조하지 않도록 이 facade를 경유한다. 구현체는 product 내부 {@code service} 패키지에 위치한다.
 *
 * <p>facade 구현은 내부에서 actorEmail → actorId 해석({@link UserDirectory}), String → ProductStatus
 * 변환, Product Entity → {@link ProductFormView} DTO 변환을 담당한다.
 * web은 String 타입만 전달하므로 도메인 enum({@code ProductStatus})을 컴파일타임에 참조하지 않는다.
 *
 * <p>소유권 검사(actorIsAdmin 판정 포함)는 기존 {@code ProductService} 위임 경로에서 그대로 수행된다.
 *
 * <p>의존 방향: web → product.spi (단방향). product는 web을 참조하지 않는다.
 */
public interface SellerProductFacade {

    /**
     * 판매자 본인 상품 목록 (최신순 페이지네이션).
     *
     * <p>actorEmail → ownerId 해석 후 본인 ownerId로만 조회(IDOR 방지).
     * <b>ADMIN 특례 없음</b> — 항상 본인 ownerId 필터. 타 판매자 상품 비노출.
     * 빈 결과는 정상(예외 없음).
     *
     * <p>반환 Page 원소 타입은 {@link SellerProductSummaryView} DTO — Entity 모듈 경계 누출 금지.
     *
     * @param actorEmail 행위자 이메일 (principal — facade 내부에서 ownerId로 해석)
     * @param pageable   페이지 정보 (size/page 사용; 정렬은 쿼리에 고정)
     * @return 판매자 본인 상품 목록 Page (DTO)
     */
    Page<SellerProductSummaryView> getMyProducts(String actorEmail, Pageable pageable);

    /**
     * 전체 카테고리 목록 조회.
     *
     * @return 카테고리 목록 DTO (flat, sortOrder ASC 정렬)
     */
    List<CategoryResponse> listCategories();

    /**
     * 상품 상태 이름 목록 반환.
     *
     * <p>web이 {@code ProductStatus} enum을 직접 참조하지 않도록 name() 문자열 목록을 제공한다.
     * View의 status 셀렉트 옵션 렌더링에 사용한다.
     *
     * @return ProductStatus 상수명 목록 (예: DRAFT, ON_SALE, SOLD_OUT, HIDDEN)
     */
    List<String> productStatusNames();

    /**
     * 상품 등록.
     *
     * <p>actorEmail → actorId 변환 후 {@code ProductService.register}에 위임한다.
     * status는 DRAFT로 고정(도메인 불변식).
     *
     * @param actorEmail  행위자 이메일 (form login session principal)
     * @param categoryId  카테고리 ID (null = 미분류)
     * @param name        상품명
     * @param description 상품 설명 (null 허용)
     * @param basePrice   기본 가격 (≥ 0)
     * @return 신규 등록된 상품 ID (productId)
     */
    long register(String actorEmail, Long categoryId, String name,
                  String description, BigDecimal basePrice);

    /**
     * 상품 수정 화면용 단건 조회.
     *
     * <p>actorEmail → actorId 변환, 소유권 검사(actorIsAdmin ADMIN 스킵) 포함.
     * Entity를 반환하지 않고 {@link ProductFormView} View DTO를 반환한다.
     *
     * @param actorEmail   행위자 이메일
     * @param actorIsAdmin 행위자 ADMIN 여부 (true면 소유권 검사 스킵)
     * @param productId    조회할 상품 ID
     * @return 상품 View DTO (status = String)
     */
    ProductFormView getForEdit(String actorEmail, boolean actorIsAdmin, long productId);

    /**
     * 상품 수정.
     *
     * <p>actorEmail → actorId 변환, 소유권 검사(actorIsAdmin ADMIN 스킵), status String → ProductStatus
     * 변환 후 {@code ProductService.update}에 위임한다.
     *
     * @param actorEmail   행위자 이메일
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    수정할 상품 ID
     * @param categoryId   수정할 카테고리 ID (null = 미분류)
     * @param name         수정할 상품명
     * @param description  수정할 상품 설명 (null 허용)
     * @param basePrice    수정할 기본 가격 (≥ 0)
     * @param status       수정할 상태 문자열 (DRAFT/ON_SALE/SOLD_OUT/HIDDEN)
     */
    void update(String actorEmail, boolean actorIsAdmin, long productId,
                Long categoryId, String name, String description,
                BigDecimal basePrice, String status);

    /**
     * 판매자 현황 페이지용 소유 상품 통계 데이터 조회.
     *
     * <p>actorEmail → ownerId 변환 후 소유 상품(페이지)과 함께
     * 상품별 재고 합계 맵, variantId ↔ productId 매핑을 반환한다.
     * web 계층이 productId/variantId를 외부 입력으로 받지 않고, 소유 검증된 데이터만 사용하도록 보장한다(IDOR 방지).
     *
     * <p>variant가 없는 상품은 stockByProduct 맵에 키가 없음(→ 0으로 처리).
     * variant가 없는 상품은 variantMappings에도 포함되지 않음(→ 판매 집계 0).
     *
     * @param actorEmail 행위자 이메일 (facade 내부에서 ownerId로 해석)
     * @param pageable   페이지 정보 (소유 상품 목록 페이지네이션)
     * @return 소유 상품 목록 + 재고맵 + variantId 매핑 번들
     */
    SellerProductStatsData getMyProductStatsData(String actorEmail, Pageable pageable);
}
