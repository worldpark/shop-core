package com.shop.shop.product.service;

import com.shop.shop.product.dto.ProductVariantCreateRequest;
import com.shop.shop.product.dto.ProductVariantResponse;
import com.shop.shop.product.dto.ProductVariantUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 상품 variant REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link ProductVariantService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다.
 *
 * <p>REST principal 추출:
 * JWT 필터 후 principal = userId(long). {@code (long) auth.getPrincipal()}로 actorId 추출.
 * actorIsAdmin: auth.getAuthorities()에 'ROLE_ADMIN' 직접 보유 여부.
 *
 * <p>레이어: *RestController → ProductVariantServiceResponse → ProductVariantService → *Repository
 */
@Service
@RequiredArgsConstructor
public class ProductVariantServiceResponse {

    private final ProductVariantService productVariantService;

    /**
     * variant 삭제 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param variantId 삭제할 variant ID
     */
    public void deleteVariant(Authentication auth, long productId, long variantId) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        productVariantService.deleteVariant(actorId, actorIsAdmin, productId, variantId);
    }

    /**
     * variant 목록 조회 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @return variant 목록 응답 DTO
     */
    public List<ProductVariantResponse> listVariants(Authentication auth, long productId) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        return productVariantService.listVariants(actorId, actorIsAdmin, productId).stream()
                .map(ProductVariantResponse::from)
                .toList();
    }

    /**
     * variant 생성 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param req       variant 생성 요청 DTO
     * @return 생성된 variant 응답 DTO
     */
    public ProductVariantResponse createVariant(Authentication auth, long productId,
                                                 ProductVariantCreateRequest req) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        List<Long> optionValueIds = req.optionValueIds() == null ? List.of() : req.optionValueIds();
        var variant = productVariantService.createVariant(actorId, actorIsAdmin, productId,
                req.sku(), req.price(), req.stock(), req.active(), optionValueIds);
        return ProductVariantResponse.from(variant);
    }

    /**
     * variant 수정 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param variantId 수정할 variant ID
     * @param req       variant 수정 요청 DTO
     * @return 수정된 variant 응답 DTO
     */
    public ProductVariantResponse updateVariant(Authentication auth, long productId, long variantId,
                                                 ProductVariantUpdateRequest req) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        List<Long> optionValueIds = req.optionValueIds() == null ? List.of() : req.optionValueIds();
        var variant = productVariantService.updateVariant(actorId, actorIsAdmin, productId, variantId,
                req.sku(), req.price(), req.stock(), req.active(), optionValueIds);
        return ProductVariantResponse.from(variant);
    }

    /**
     * ROLE_ADMIN 직접 보유 여부 판정.
     */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
