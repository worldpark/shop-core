package com.shop.shop.product.service;

import com.shop.shop.common.exception.DuplicateOptionNameException;
import com.shop.shop.common.exception.DuplicateOptionValueException;
import com.shop.shop.common.exception.OptionNotFoundException;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 상품 옵션 / 옵션값 도메인 서비스.
 *
 * <p>불변식:
 * <ul>
 *   <li>V1: 동일 상품 내 옵션명 중복 금지 ({@link DuplicateOptionNameException} 409)</li>
 *   <li>V2: 동일 옵션 내 옵션값 중복 금지 ({@link DuplicateOptionValueException} 409)</li>
 *   <li>V10: optionId가 productId 하위 리소스 ({@link OptionNotFoundException} 404)</li>
 *   <li>V12/V13: 소유권·상품 미존재 ({@link ProductService#getOwnedProduct} 위임)</li>
 * </ul>
 *
 * <p>검증 우선순위: 소유권/하위리소스(404)를 중복/범위(409)보다 먼저 검사.
 *
 * <p><b>순수 도메인 — member 의존 없음.</b>
 * actorId(long)/actorIsAdmin(boolean)을 인자로만 받는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProductOptionService {

    private final ProductService productService;
    private final ProductOptionRepository productOptionRepository;
    private final OptionValueRepository optionValueRepository;

    /**
     * 옵션 생성.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>소유권 검사 (V12/V13) — getOwnedProduct 위임</li>
     *   <li>옵션명 중복 검사 (V1)</li>
     *   <li>ProductOption.create + 저장</li>
     * </ol>
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param name         옵션명
     * @return 저장된 ProductOption Entity
     */
    public ProductOption createOption(long actorId, boolean actorIsAdmin, long productId, String name) {
        Product product = productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        if (productOptionRepository.existsByProductIdAndName(productId, name)) {
            throw new DuplicateOptionNameException(productId, name);
        }

        ProductOption option = ProductOption.create(product, name);
        return productOptionRepository.save(option);
    }

    /**
     * 옵션값 생성.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>소유권 검사 (V12/V13)</li>
     *   <li>optionId가 productId 하위 리소스 검사 (V10)</li>
     *   <li>옵션값 중복 검사 (V2)</li>
     *   <li>OptionValue.create + 저장</li>
     * </ol>
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @param optionId     대상 옵션 ID
     * @param value        옵션값 문자열
     * @return 저장된 OptionValue Entity
     */
    public OptionValue createOptionValue(long actorId, boolean actorIsAdmin,
                                         long productId, long optionId, String value) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);

        ProductOption option = productOptionRepository.findById(optionId)
                .filter(o -> o.getProduct().getId().equals(productId))
                .orElseThrow(() -> new OptionNotFoundException(optionId));

        if (optionValueRepository.existsByOptionIdAndValue(optionId, value)) {
            throw new DuplicateOptionValueException(optionId, value);
        }

        OptionValue optionValue = OptionValue.create(option, value);
        return optionValueRepository.save(optionValue);
    }

    /**
     * 상품 옵션 목록 조회 (옵션값 포함).
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자 ADMIN 여부
     * @param productId    대상 상품 ID
     * @return 옵션 목록 (id 순 정렬)
     */
    @Transactional(readOnly = true)
    public List<ProductOption> listOptions(long actorId, boolean actorIsAdmin, long productId) {
        productService.getOwnedProduct(actorId, actorIsAdmin, productId);
        return productOptionRepository.findByProductIdOrderById(productId);
    }
}
