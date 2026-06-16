package com.shop.shop.product.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.DuplicateSkuException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 상품 variant 도메인 서비스.
 *
 * <p>불변식:
 * <ul>
 *   <li>V3: SKU 전역 중복 금지 ({@link DuplicateSkuException} 409)</li>
 *   <li>V4: price ≥ 0 ({@link BusinessException} 400)</li>
 *   <li>V5: stock ≥ 0 ({@link BusinessException} 400)</li>
 *   <li>V6: 모든 optionValueId가 해당 productId 소속 ({@link BusinessException} 400)</li>
 *   <li>V7: 한 옵션당 최대 1개 optionValue 선택 ({@link BusinessException} 400)</li>
 *   <li>V8: 상품에 옵션이 있으면 각 옵션마다 1개씩 전부 커버 ({@link BusinessException} 400)</li>
 *   <li>V9: 동일 optionValue 조합 variant 중복 금지 ({@link BusinessException} 409)</li>
 *   <li>V11: variantId가 productId 하위 리소스 ({@link VariantNotFoundException} 404)</li>
 *   <li>V12/V13: 소유권·상품 미존재 ({@link ProductService#getOwnedProduct} 위임)</li>
 * </ul>
 *
 * <p>검증 우선순위: 소유권/하위리소스(404)를 중복/범위(400/409)보다 먼저 검사.
 *
 * <p><b>순수 도메인 — member 의존 없음.</b>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProductVariantService {

    private final ProductService productService;
    private final ProductOptionRepository productOptionRepository;
    private final OptionValueRepository optionValueRepository;
    private final ProductVariantRepository productVariantRepository;

    /**
     * variant 생성.
     *
     * <p>처리 순서: V12/V13 → V3 → V4 → V5 → V6 → V7 → V8 → V9 → 저장.
     *
     * @param actorId        행위자 userId
     * @param actorIsAdmin   행위자 ADMIN 여부
     * @param productId      대상 상품 ID
     * @param sku            SKU
     * @param price          가격 (≥ 0)
     * @param stock          재고 (≥ 0)
     * @param isActive       활성 여부
     * @param optionValueIds 선택 옵션값 ID 목록
     * @return 저장된 ProductVariant Entity
     */
    public ProductVariant createVariant(long actorId, boolean actorIsAdmin, long productId,
                                         String sku, BigDecimal price, int stock, boolean isActive,
                                         List<Long> optionValueIds) {
        Product product = productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        validateSku(sku, null);
        validatePrice(price);
        validateStock(stock);

        List<Long> ids = optionValueIds == null ? List.of() : optionValueIds;
        Set<OptionValue> optionValues = resolveAndValidateOptionValues(productId, ids, null);

        ProductVariant variant = ProductVariant.create(product, sku, price, stock, isActive, optionValues);
        return productVariantRepository.save(variant);
    }

    /**
     * variant 수정.
     *
     * <p>처리 순서: V12/V13 → V11 → V3(자기제외) → V4 → V5 → V6 → V7 → V8 → V9(자기제외) → dirty checking.
     *
     * @param actorId        행위자 userId
     * @param actorIsAdmin   행위자 ADMIN 여부
     * @param productId      대상 상품 ID
     * @param variantId      수정할 variant ID
     * @param sku            수정할 SKU
     * @param price          수정할 가격 (≥ 0)
     * @param stock          수정할 재고 (≥ 0)
     * @param isActive       수정할 활성 여부
     * @param optionValueIds 수정할 옵션값 ID 목록
     * @return 수정된 ProductVariant Entity
     */
    public ProductVariant updateVariant(long actorId, boolean actorIsAdmin, long productId, long variantId,
                                         String sku, BigDecimal price, int stock, boolean isActive,
                                         List<Long> optionValueIds) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        ProductVariant variant = productVariantRepository.findById(variantId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(VariantNotFoundException::new);

        validateSku(sku, variantId);
        validatePrice(price);
        validateStock(stock);

        List<Long> ids = optionValueIds == null ? List.of() : optionValueIds;
        Set<OptionValue> optionValues = resolveAndValidateOptionValues(productId, ids, variantId);

        variant.update(sku, price, stock, isActive, optionValues);
        return variant;
    }

    /**
     * variant 삭제.
     *
     * <p>처리 순서: V12/V13 → V11 → 삭제.
     * order_items.variant_id는 ON DELETE SET NULL이므로 주문 스냅샷 보존.
     * VariantStock은 product_variants와 동일 물리 테이블로 함께 삭제됨.
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param variantId    삭제할 variant ID
     */
    public void deleteVariant(long actorId, boolean actorIsAdmin, long productId, long variantId) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        ProductVariant variant = productVariantRepository.findById(variantId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(VariantNotFoundException::new);

        productVariantRepository.delete(variant);
    }

    /**
     * 상품 variant 목록 조회.
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @return variant 목록
     */
    @Transactional(readOnly = true)
    public List<ProductVariant> listVariants(long actorId, boolean actorIsAdmin, long productId) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);
        return productVariantRepository.findByProductId(productId);
    }

    // ============================================================
    // 검증 헬퍼
    // ============================================================

    /**
     * SKU 중복 검사 (V3).
     * variantId가 null이면 신규 생성, 있으면 자기 자신 제외 중복 검사.
     */
    private void validateSku(String sku, Long variantId) {
        boolean duplicated = variantId == null
                ? productVariantRepository.existsBySku(sku)
                : productVariantRepository.existsBySkuAndIdNot(sku, variantId);
        if (duplicated) {
            throw new DuplicateSkuException(sku);
        }
    }

    /**
     * price ≥ 0 검사 (V4).
     */
    private void validatePrice(BigDecimal price) {
        if (price != null && price.signum() < 0) {
            throw new BusinessException("가격은 0 이상이어야 합니다.", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * stock ≥ 0 검사 (V5).
     */
    private void validateStock(int stock) {
        if (stock < 0) {
            throw new BusinessException("재고는 0 이상이어야 합니다.", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 옵션값 검증 및 해석 (V6, V7, V8, V9).
     *
     * @param productId        상품 ID
     * @param optionValueIds   요청 optionValueId 목록
     * @param excludeVariantId 수정 시 자기 자신 variantId (신규 생성이면 null)
     * @return 해석된 OptionValue 집합
     */
    private Set<OptionValue> resolveAndValidateOptionValues(long productId, List<Long> optionValueIds,
                                                             Long excludeVariantId) {
        // V6: 상품 소속 optionValue id 집합 구성
        List<OptionValue> productOptionValues = optionValueRepository.findByOption_ProductId(productId);
        Set<Long> validOptionValueIds = productOptionValues.stream()
                .map(OptionValue::getId)
                .collect(Collectors.toSet());

        Set<Long> requestIds = new HashSet<>(optionValueIds);
        if (!validOptionValueIds.containsAll(requestIds)) {
            throw new BusinessException("요청한 옵션값이 해당 상품에 속하지 않습니다.", HttpStatus.BAD_REQUEST);
        }

        // 요청 id → OptionValue 매핑
        Map<Long, OptionValue> optionValueMap = productOptionValues.stream()
                .collect(Collectors.toMap(OptionValue::getId, ov -> ov));
        Set<OptionValue> selectedOptionValues = requestIds.stream()
                .map(optionValueMap::get)
                .collect(Collectors.toSet());

        // V7: 한 옵션당 최대 1개 optionValue
        Map<Long, Long> optionIdCount = selectedOptionValues.stream()
                .collect(Collectors.groupingBy(
                        ov -> ov.getOption().getId(),
                        Collectors.counting()
                ));
        if (optionIdCount.values().stream().anyMatch(count -> count > 1)) {
            throw new BusinessException("한 옵션에서는 최대 1개의 값만 선택할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }

        // V8: 상품 옵션 전부 커버
        List<ProductOption> productOptions = productOptionRepository.findByProductIdOrderById(productId);
        if (!productOptions.isEmpty()) {
            Set<Long> productOptionIds = productOptions.stream()
                    .map(ProductOption::getId)
                    .collect(Collectors.toSet());
            Set<Long> selectedOptionIds = selectedOptionValues.stream()
                    .map(ov -> ov.getOption().getId())
                    .collect(Collectors.toSet());
            if (!productOptionIds.equals(selectedOptionIds)) {
                throw new BusinessException("상품의 모든 옵션에 각각 1개씩 값을 선택해야 합니다.", HttpStatus.BAD_REQUEST);
            }
        }

        // V9: 동일 조합 중복 검사 (수정 시 자기 자신 제외)
        List<ProductVariant> existingVariants = productVariantRepository.findByProductId(productId);
        for (ProductVariant existingVariant : existingVariants) {
            if (excludeVariantId != null && existingVariant.getId().equals(excludeVariantId)) {
                continue;
            }
            Set<Long> existingOptionValueIds = existingVariant.getOptionValues().stream()
                    .map(OptionValue::getId)
                    .collect(Collectors.toSet());
            if (existingOptionValueIds.equals(requestIds)) {
                throw new BusinessException("동일한 옵션 조합의 variant가 이미 존재합니다.", HttpStatus.CONFLICT);
            }
        }

        return selectedOptionValues;
    }
}
