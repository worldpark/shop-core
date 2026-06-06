package com.shop.shop.product.service;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.dto.ProductImageManagementView;
import com.shop.shop.product.dto.ProductImageResponse;
import com.shop.shop.product.dto.SellerProductRef;
import com.shop.shop.product.spi.SellerProductImageFacade;
import com.shop.shop.product.spi.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * {@link SellerProductImageFacade} 구현체.
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link SellerProductImageFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>actorEmail → actorId 변환: {@link UserDirectory#findUserIdByEmail(String)}</li>
 *   <li>{@link ProductImageService} 위임</li>
 *   <li>Entity → DTO 변환</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class SellerProductImageFacadeImpl implements SellerProductImageFacade {

    private final ProductService productService;
    private final ProductImageService productImageService;
    private final AssetUrlResolver assetUrlResolver;
    private final UserDirectory userDirectory;

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductImageManagementView getManagementView(String actorEmail, boolean actorIsAdmin, long productId) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);

        Product product = productService.getOwnedProduct(actorId, actorIsAdmin, productId);
        SellerProductRef productRef = new SellerProductRef(product.getId(), product.getName());

        List<ProductImage> images = productImageService.listImages(actorId, actorIsAdmin, productId);
        List<ProductImageResponse> imageResponses = images.stream()
                .map(image -> ProductImageResponse.from(image, assetUrlResolver))
                .toList();

        return new ProductImageManagementView(productRef, imageResponses);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductImageResponse upload(String actorEmail, boolean actorIsAdmin, long productId,
                                       String originalFilename, String contentType, InputStream inputStream) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        ProductImage image = productImageService.upload(actorId, actorIsAdmin, productId,
                originalFilename, contentType, inputStream);
        return ProductImageResponse.from(image, assetUrlResolver);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductImageResponse setPrimary(String actorEmail, boolean actorIsAdmin, long productId, long imageId) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        ProductImage image = productImageService.setPrimary(actorId, actorIsAdmin, productId, imageId);
        return ProductImageResponse.from(image, assetUrlResolver);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductImageResponse changeOrder(String actorEmail, boolean actorIsAdmin, long productId, long imageId,
                                            int sortOrder) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        ProductImage image = productImageService.changeOrder(actorId, actorIsAdmin, productId, imageId, sortOrder);
        return ProductImageResponse.from(image, assetUrlResolver);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(String actorEmail, boolean actorIsAdmin, long productId, long imageId) {
        long actorId = userDirectory.findUserIdByEmail(actorEmail);
        productImageService.delete(actorId, actorIsAdmin, productId, imageId);
    }
}
