package com.shop.shop.product.service;

import com.shop.shop.product.dto.OptionValueCreateRequest;
import com.shop.shop.product.dto.OptionValueResponse;
import com.shop.shop.product.dto.ProductOptionCreateRequest;
import com.shop.shop.product.dto.ProductOptionResponse;
import com.shop.shop.product.repository.OptionValueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 상품 옵션 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — {@link ProductOptionService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다.
 *
 * <p>REST principal 추출:
 * JWT 필터 후 principal = userId(long). {@code (long) auth.getPrincipal()}로 actorId 추출.
 * actorIsAdmin: auth.getAuthorities()에 'ROLE_ADMIN' 직접 보유 여부.
 *
 * <p>레이어: *RestController → ProductOptionServiceResponse → ProductOptionService → *Repository
 */
@Service
@RequiredArgsConstructor
public class ProductOptionServiceResponse {

    private final ProductOptionService productOptionService;
    private final OptionValueRepository optionValueRepository;

    /**
     * 옵션 삭제 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param optionId  삭제할 옵션 ID
     */
    public void deleteOption(Authentication auth, long productId, long optionId) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        productOptionService.deleteOption(actorId, actorIsAdmin, productId, optionId);
    }

    /**
     * 옵션 목록 조회 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @return 옵션 목록 응답 DTO (옵션값 포함)
     */
    public List<ProductOptionResponse> listOptions(Authentication auth, long productId) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        return productOptionService.listOptions(actorId, actorIsAdmin, productId).stream()
                .map(option -> ProductOptionResponse.from(
                        option,
                        optionValueRepository.findByOptionIdOrderById(option.getId())
                ))
                .toList();
    }

    /**
     * 옵션 생성 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param req       옵션 생성 요청 DTO
     * @return 생성된 옵션 응답 DTO
     */
    public ProductOptionResponse createOption(Authentication auth, long productId,
                                              ProductOptionCreateRequest req) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        var option = productOptionService.createOption(actorId, actorIsAdmin, productId, req.name());
        return ProductOptionResponse.from(option, List.of());
    }

    /**
     * 옵션값 생성 — REST 전용.
     *
     * @param auth      JWT 인증 객체
     * @param productId 대상 상품 ID
     * @param optionId  대상 옵션 ID
     * @param req       옵션값 생성 요청 DTO
     * @return 생성된 옵션값 응답 DTO
     */
    public OptionValueResponse createOptionValue(Authentication auth, long productId,
                                                  long optionId, OptionValueCreateRequest req) {
        long actorId = (long) auth.getPrincipal();
        boolean actorIsAdmin = isAdmin(auth);
        var optionValue = productOptionService.createOptionValue(actorId, actorIsAdmin,
                productId, optionId, req.value());
        return OptionValueResponse.from(optionValue);
    }

    /**
     * ROLE_ADMIN 직접 보유 여부 판정.
     */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
