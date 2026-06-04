package com.shop.shop.product.service;

import com.shop.shop.product.dto.ProductCreateRequest;
import com.shop.shop.product.dto.ProductResponse;
import com.shop.shop.product.dto.ProductUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 상품 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link ProductService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다 (Constraint).
 *
 * <p>REST principal 통일(008 §1.3 계승):
 * JWT 필터 후 principal = userId(long). {@code (long) auth.getPrincipal()}로 actorId 추출.
 * member 의존 없음 — principal에 이미 userId가 있어 UserDirectory 포트도 불요.
 * actorIsAdmin: auth.getAuthorities()에 'ROLE_ADMIN' 직접 보유 여부(RoleHierarchy 함의 제외).
 *
 * <p>레이어: *RestController → ProductServiceResponse → ProductService → *Repository
 */
@Service
@RequiredArgsConstructor
public class ProductServiceResponse {

    private final ProductService productService;

    /**
     * 상품 등록 — REST 전용.
     *
     * <p>principal=userId(long) 추출 → ProductService.register 위임.
     *
     * @param auth JWT 인증 객체 (principal=userId long)
     * @param req  등록 요청 DTO
     * @return 등록된 상품 응답 DTO
     */
    public ProductResponse register(Authentication auth, ProductCreateRequest req) {
        long actorId = (long) auth.getPrincipal();
        return ProductResponse.from(
                productService.register(actorId, req.categoryId(), req.name(), req.description(), req.basePrice())
        );
    }

    /**
     * 상품 수정 — REST 전용.
     *
     * <p>principal=userId(long) + isAdmin 추출 → ProductService.update 위임.
     *
     * @param auth      JWT 인증 객체 (principal=userId long)
     * @param productId 수정할 상품 ID
     * @param req       수정 요청 DTO
     * @return 수정된 상품 응답 DTO
     */
    public ProductResponse update(Authentication auth, long productId, ProductUpdateRequest req) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        return ProductResponse.from(
                productService.update(actorId, actorIsAdmin, productId,
                        req.categoryId(), req.name(), req.description(), req.basePrice(), req.status())
        );
    }

    /**
     * ROLE_ADMIN 직접 보유 여부 판정.
     * RoleHierarchy 함의가 아닌 원본 ROLE_ADMIN 직접 보유로 판정한다.
     */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
