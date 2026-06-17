package com.shop.shop.product.service;

import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.ProductFormView;
import com.shop.shop.product.dto.ProductStockSum;
import com.shop.shop.product.dto.SellerProductStatsData;
import com.shop.shop.product.dto.SellerProductSummaryView;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.product.spi.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link SellerProductFacade} 구현체.
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link SellerProductFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>actorEmail → actorId 변환: {@link UserDirectory#findUserIdByEmail(String)}</li>
 *   <li>status(String) → {@link ProductStatus} 변환</li>
 *   <li>{@link Product} Entity → {@link ProductFormView} DTO 변환</li>
 *   <li>{@link com.shop.shop.product.domain.Category} Entity → {@link CategoryResponse} DTO 변환</li>
 * </ul>
 *
 * <p>소유권/권한 검사는 {@link ProductService#getForEdit}/{@link ProductService#update}
 * 위임 경로에서 그대로 수행된다. facade는 actorIsAdmin을 그대로 전달한다.
 */
@Service
@RequiredArgsConstructor
class SellerProductFacadeImpl implements SellerProductFacade {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final UserDirectory userDirectory;
    private final ProductVariantRepository productVariantRepository;

    /**
     * {@inheritDoc}
     *
     * <p>{@link CategoryService#list()} 결과를 {@link CategoryResponse#from(com.shop.shop.product.domain.Category)}로 변환한다.
     */
    @Override
    public List<CategoryResponse> listCategories() {
        return categoryService.list().stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link ProductStatus#values()} 각 상수의 {@code name()}을 반환한다.
     * web이 {@code ProductStatus} enum을 직접 참조하지 않도록 문자열 목록으로 제공한다.
     */
    @Override
    public List<String> productStatusNames() {
        return Arrays.stream(ProductStatus.values())
                .map(ProductStatus::name)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     *
     * <p>actorEmail → ownerId 변환 후 {@link ProductService#getMyProducts}에 위임한다.
     * {@link Page#map(java.util.function.Function)}으로 Entity Page를 DTO Page로 즉시 변환.
     * Entity는 facade 반환 타입에 포함되지 않는다(모듈 경계 누출 금지).
     */
    @Override
    public Page<SellerProductSummaryView> getMyProducts(String actorEmail, Pageable pageable) {
        long ownerId = userDirectory.findUserIdByEmail(actorEmail);
        return productService.getMyProducts(ownerId, pageable)
                .map(SellerProductSummaryView::from);
    }

    /**
     * {@inheritDoc}
     *
     * <p>actorEmail → actorId 변환 후 {@link ProductService#register}에 위임한다.
     * 반환된 Product의 ID를 long으로 반환한다.
     */
    @Override
    public long register(String actorEmail, Long categoryId, String name,
                         String description, BigDecimal basePrice) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        Product product = productService.register(actorId, categoryId, name, description, basePrice);
        return product.getId();
    }

    /**
     * {@inheritDoc}
     *
     * <p>actorEmail → actorId 변환, 소유권 검사(actorIsAdmin ADMIN 스킵) 포함.
     * {@link ProductService#getForEdit}에 위임 후 {@link ProductFormView#from(Product)}로 변환한다.
     */
    @Override
    public ProductFormView getForEdit(String actorEmail, boolean actorIsAdmin, long productId) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        Product product = productService.getForEdit(actorId, actorIsAdmin, productId);
        return ProductFormView.from(product);
    }

    /**
     * {@inheritDoc}
     *
     * <p>actorEmail → actorId 변환, status(String) → {@link ProductStatus} 변환 후
     * {@link ProductService#update}에 위임한다.
     */
    @Override
    public void update(String actorEmail, boolean actorIsAdmin, long productId,
                       Long categoryId, String name, String description,
                       BigDecimal basePrice, String status) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        ProductStatus productStatus = ProductStatus.valueOf(status);
        productService.update(actorId, actorIsAdmin, productId, categoryId, name, description, basePrice, productStatus);
    }

    /**
     * {@inheritDoc}
     *
     * <p>actorEmail → ownerId 변환 후 소유 상품 페이지, 재고 합계 맵, variantId 매핑을 한 번에 조회한다.
     * web 계층이 productId/variantId를 외부에서 입력받지 않고 소유 검증된 데이터만 사용하도록 보장한다(IDOR 방지).
     */
    @Override
    @Transactional(readOnly = true)
    public SellerProductStatsData getMyProductStatsData(String actorEmail, Pageable pageable) {
        long ownerId = userDirectory.findUserIdByEmail(actorEmail);
        Page<Product> productPage = productService.getMyProducts(ownerId, pageable);

        List<SellerProductSummaryView> products = productPage.getContent().stream()
                .map(SellerProductSummaryView::from)
                .collect(Collectors.toList());

        List<Long> productIds = products.stream()
                .map(SellerProductSummaryView::productId)
                .collect(Collectors.toList());

        Map<Long, Long> stockByProduct;
        List<VariantProductMapping> variantMappings;

        if (productIds.isEmpty()) {
            stockByProduct = Map.of();
            variantMappings = List.of();
        } else {
            List<ProductStockSum> stockSums = productVariantRepository.findStockSumsByProductIdIn(productIds);
            stockByProduct = stockSums.stream()
                    .collect(Collectors.toMap(ProductStockSum::productId, ProductStockSum::totalStock));
            variantMappings = productVariantRepository.findVariantProductMappingsByProductIdIn(productIds);
        }

        return new SellerProductStatsData(products, productPage.getTotalElements(), stockByProduct, variantMappings);
    }

    /**
     * {@inheritDoc}
     *
     * <p>actorEmail → ownerId 변환 후 {@link ProductVariantRepository#findVariantProductMappingsByOwnerId}
     * 1쿼리로 소유 전체 variant 매핑을 조회한다(N+1 없음, IDOR 안전).
     * SSE 연결 시점에 1회 호출되어 registry에 캐시된다.
     * 연결 유지 중 신규 등록 상품은 reconnect 전까지 반영되지 않는다(staleness 정책).
     */
    @Override
    @Transactional(readOnly = true)
    public List<VariantProductMapping> getMyOwnedVariantMappings(String actorEmail) {
        long ownerId = userDirectory.findUserIdByEmail(actorEmail);
        return productVariantRepository.findVariantProductMappingsByOwnerId(ownerId);
    }
}
