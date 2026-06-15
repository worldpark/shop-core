package com.shop.shop.product.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.inventory.spi.InventoryStockPort.StockLedgerView;
import com.shop.shop.product.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 조정 도메인 서비스 (product 모듈 소유, inventory.spi 위임).
 *
 * <p>소유권 게이트({@link ProductService#getOwnedProduct})와
 * variant↔product 소속 검증({@link ProductVariantRepository})을 담당하고
 * 재고 변경·원장 적재는 {@link InventoryStockPort}로 위임한다.
 *
 * <p>이 배치는 소유권({@code Product.ownerId})이 product 모듈 소유이므로
 * inventory가 판정 불가한 소유권 검사를 product 경계에서 native 해결하기 위함이다(plan §1.3).
 * 의존 방향: product → inventory.spi 단방향(order 선례 동형, Modulith @NamedInterface("spi") 허용).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StockAdjustmentService {

    private final ProductService productService;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryStockPort inventoryStockPort;

    /**
     * 운영자 재고 조정.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>소유권 검증: {@link ProductService#getOwnedProduct} — 상품 미존재(404)·소유권 위반(404)</li>
     *   <li>variant↔product 소속 검증: variantId가 productId 하위 리소스인지 — 미소속(404)</li>
     *   <li>memo 누락 검증(서비스 2차 — Bean Validation 1차): 공란이면 400</li>
     *   <li>delta=0 검증: 변동 없음(400)</li>
     *   <li>{@link InventoryStockPort#adjustStock} 위임 — 음수 재고(409)·variant 미존재(404) inventory 책임.
     *       포트가 방금 적재한 원장을 즉시 반환(재조회 없음)</li>
     * </ol>
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param variantId    대상 variant ID
     * @param delta        부호 있는 조정량 (0 불허)
     * @param memo         조정 사유 (필수)
     * @return 조정 결과 원장 뷰 (before/after/occurred_at)
     * @throws com.shop.shop.common.exception.ProductNotFoundException     상품 미존재(404)
     * @throws com.shop.shop.common.exception.ProductAccessDeniedException 소유권 위반(404)
     * @throws VariantNotFoundException                                    variant 미존재·소속 불일치(404)
     * @throws BusinessException                                           memo 공란·delta=0(400)
     * @throws com.shop.shop.common.exception.InsufficientStockException  조정 결과 음수(409)
     */
    public StockLedgerView adjustStock(long actorId, boolean actorIsAdmin,
                                        long productId, long variantId, int delta, String memo) {
        // 1. 소유권 검증 (상품 미존재·타인 소유 → 404)
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        // 2. variant↔product 소속 검증
        productVariantRepository.findById(variantId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(VariantNotFoundException::new);

        // 3. memo 누락 검증 (서비스 2차 — Bean Validation 1차)
        if (memo == null || memo.isBlank()) {
            throw new BusinessException("조정 사유(memo)는 필수입니다.", HttpStatus.BAD_REQUEST);
        }

        // 4. delta=0 검증
        if (delta == 0) {
            throw new BusinessException("조정량(delta)은 0이 될 수 없습니다.", HttpStatus.BAD_REQUEST);
        }

        // 5. inventory 위임 (음수 재고→409, variant 미존재→404 inventory 책임)
        // 포트가 방금 적재한 원장을 StockLedgerView로 즉시 반환 — 재조회 불필요
        return inventoryStockPort.adjustStock(variantId, delta, actorId, memo);
    }

    /**
     * 재고 변동 원장 조회.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>소유권 검증 (소속 검증 포함) — adjustStock과 동일</li>
     *   <li>{@link InventoryStockPort#getLedger} 위임</li>
     * </ol>
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param variantId    대상 variant ID
     * @param pageable     페이지 정보
     * @return 원장 뷰 Page (occurred_at DESC)
     */
    @Transactional(readOnly = true)
    public Page<StockLedgerView> getLedger(long actorId, boolean actorIsAdmin,
                                            long productId, long variantId, Pageable pageable) {
        // 1. 소유권 검증
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        // 2. variant↔product 소속 검증
        productVariantRepository.findById(variantId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(VariantNotFoundException::new);

        // 3. inventory 위임
        return inventoryStockPort.getLedger(variantId, pageable);
    }
}
