package com.shop.shop.product.service;

import com.shop.shop.inventory.spi.InventoryStockPort.StockLedgerView;
import com.shop.shop.product.dto.StockAdjustmentRequest;
import com.shop.shop.product.dto.StockAdjustmentResponse;
import com.shop.shop.product.dto.StockLedgerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 재고 조정 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다(architecture-rule).
 * 비즈니스 로직 없음 — {@link StockAdjustmentService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다.
 *
 * <p>REST principal 추출:
 * JWT 필터 후 principal = userId(long). {@code (long) auth.getPrincipal()}로 actorId 추출.
 * actorIsAdmin: auth.getAuthorities()에 'ROLE_ADMIN' 직접 보유 여부.
 *
 * <p>occurred_at KST 렌더: {@link StockAdjustmentResponse#of}·{@link StockLedgerResponse#from} 팩토리가 담당.
 *
 * <p>레이어: SellerStockAdjustmentRestController → StockAdjustmentServiceResponse → StockAdjustmentService
 */
@Service
@RequiredArgsConstructor
public class StockAdjustmentServiceResponse {

    private final StockAdjustmentService stockAdjustmentService;

    /**
     * 재고 조정 — REST 전용.
     *
     * <p>{@link StockAdjustmentService#adjustStock}이 조정 후 최신 원장 뷰를 반환하므로
     * 이를 {@link StockAdjustmentResponse}로 변환한다.
     * 이중 소유권 검증을 피하기 위해 서비스 계층에서 원장 조회까지 단일 트랜잭션으로 처리한다.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param variantId 대상 variant ID
     * @param req       조정 요청 DTO
     * @return 조정 결과 응답 DTO
     */
    public StockAdjustmentResponse adjustStock(Authentication auth, long productId, long variantId,
                                                StockAdjustmentRequest req) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);

        StockLedgerView result = stockAdjustmentService.adjustStock(
                actorId, actorIsAdmin, productId, variantId, req.delta(), req.memo());

        return StockAdjustmentResponse.of(result);
    }

    /**
     * 재고 변동 원장 조회 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param variantId 대상 variant ID
     * @param pageable  페이지 정보
     * @return 원장 응답 DTO Page
     */
    public Page<StockLedgerResponse> getLedger(Authentication auth, long productId, long variantId,
                                                Pageable pageable) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);

        return stockAdjustmentService.getLedger(actorId, actorIsAdmin, productId, variantId, pageable)
                .map(StockLedgerResponse::from);
    }

    /**
     * ROLE_ADMIN 직접 보유 여부 판정.
     */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
