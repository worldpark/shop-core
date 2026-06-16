package com.shop.shop.product.service;

import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductOptionResponse;
import com.shop.shop.product.dto.ProductVariantResponse;
import com.shop.shop.product.dto.SellerProductRef;
import com.shop.shop.product.dto.VariantManagementView;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.spi.SellerProductVariantFacade;
import com.shop.shop.product.spi.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@link SellerProductVariantFacade} 구현체.
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link SellerProductVariantFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>actorEmail → actorId 변환: {@link UserDirectory#findUserIdByEmail(String)}</li>
 *   <li>두 서비스({@link ProductOptionService}, {@link ProductVariantService}) 위임</li>
 *   <li>Entity → DTO 변환</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class SellerProductVariantFacadeImpl implements SellerProductVariantFacade {

    private final ProductService productService;
    private final ProductOptionService productOptionService;
    private final ProductVariantService productVariantService;
    private final OptionValueRepository optionValueRepository;
    private final UserDirectory userDirectory;

    /**
     * {@inheritDoc}
     */
    @Override
    public VariantManagementView getManagementView(String actorEmail, boolean actorIsAdmin, long productId) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);

        var product = productService.getOwnedProduct(actorId, actorIsAdmin, productId);
        SellerProductRef productRef = new SellerProductRef(product.getId(), product.getName(), product.getBasePrice());

        List<ProductOption> options = productOptionService.listOptions(actorId, actorIsAdmin, productId);
        List<ProductOptionResponse> optionResponses = options.stream()
                .map(option -> ProductOptionResponse.from(
                        option,
                        optionValueRepository.findByOptionIdOrderById(option.getId())
                ))
                .toList();

        List<ProductVariant> variants = productVariantService.listVariants(actorId, actorIsAdmin, productId);
        List<ProductVariantResponse> variantResponses = variants.stream()
                .map(ProductVariantResponse::from)
                .toList();

        return new VariantManagementView(productRef, optionResponses, variantResponses);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createOption(String actorEmail, boolean actorIsAdmin, long productId, String name) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        productOptionService.createOption(actorId, actorIsAdmin, productId, name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createOptionValue(String actorEmail, boolean actorIsAdmin, long productId,
                                  long optionId, String value) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        productOptionService.createOptionValue(actorId, actorIsAdmin, productId, optionId, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createVariant(String actorEmail, boolean actorIsAdmin, long productId,
                              String sku, BigDecimal price, int stock, boolean active,
                              List<Long> optionValueIds) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        productVariantService.createVariant(actorId, actorIsAdmin, productId, sku, price, stock, active,
                optionValueIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateVariant(String actorEmail, boolean actorIsAdmin, long productId, long variantId,
                              String sku, BigDecimal price, int stock, boolean active,
                              List<Long> optionValueIds) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        productVariantService.updateVariant(actorId, actorIsAdmin, productId, variantId, sku, price, stock,
                active, optionValueIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteOption(String actorEmail, boolean actorIsAdmin, long productId, long optionId) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        productOptionService.deleteOption(actorId, actorIsAdmin, productId, optionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteVariant(String actorEmail, boolean actorIsAdmin, long productId, long variantId) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        productVariantService.deleteVariant(actorId, actorIsAdmin, productId, variantId);
    }
}
