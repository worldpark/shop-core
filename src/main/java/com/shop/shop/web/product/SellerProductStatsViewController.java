package com.shop.shop.web.product;

import com.shop.shop.web.product.dto.SellerProductStatsRow;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 판매자 상품 현황 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>절차:
 * <ol>
 *   <li>소유 상품 페이지 + variantId·재고맵 조회(소유 검증 포함) via {@link SellerProductStatsAssembler}</li>
 *   <li>전체 variantId로 {@code SellerSalesStatsPort.aggregateByVariantIds} 호출(assembler 내부)</li>
 *   <li>variantId→productId 매핑으로 상품별 합산(assembler 내부)</li>
 *   <li>행 DTO 리스트 → 모델 바인딩 → {@code seller/product-stats} 렌더</li>
 * </ol>
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code statsPage} — {@link Page}&lt;{@link SellerProductStatsRow}&gt;</li>
 * </ul>
 * View name: {@code seller/product-stats}
 *
 * <p>IDOR: variantId 집합은 항상 소유 검증된 상품에서만 파생된다.
 * 컨트롤러에서 productId/variantId를 외부 입력으로 받지 않는다.
 */
@Controller
@RequestMapping("/seller/products/stats")
@RequiredArgsConstructor
public class SellerProductStatsViewController {

    private static final String STATS_VIEW = "seller/product-stats";

    private final SellerProductStatsAssembler assembler;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 판매자 상품 현황 페이지.
     * GET /seller/products/stats
     *
     * <p>소유 상품별로 총재고·판매수량·매출을 집계해 표시한다.
     * 모델 키 {@code statsPage} — Thymeleaf 예약어 회피.
     *
     * @param auth     SecurityContext 인증 객체
     * @param pageable 페이지 정보 (기본 size=10, createdAt DESC 고정)
     * @param model    Spring MVC 모델
     * @return view name "seller/product-stats"
     */
    @GetMapping
    public String stats(
            Authentication auth,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        Page<SellerProductStatsRow> statsPage = assembler.assemble(actor.email(), pageable);

        model.addAttribute("statsPage", statsPage);
        return STATS_VIEW;
    }
}
