package com.shop.shop.common.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정적 자산 URL 합성 컴포넌트.
 *
 * <p>URL 합성의 단일 책임 지점 (static-asset-rule).
 * Controller · Service · Template에 base URL 하드코딩 금지.
 * DB에는 storageKey만 저장하고, URL 합성은 이 클래스를 통해서만 수행한다.
 *
 * <p>합성 공식: {@code assetBaseUrl + publicPrefix + "/" + storageKey}
 * 예: {@code http://localhost:8080/assets/products/10/uuid.jpg}
 */
@Component
@RequiredArgsConstructor
public class AssetUrlResolver {

    private final StorageProperties storageProperties;

    /**
     * storageKey를 공개 URL로 변환한다.
     *
     * @param storageKey DB에 저장된 storageKey (예: products/10/uuid.jpg)
     * @return 공개 URL (예: http://localhost:8080/assets/products/10/uuid.jpg)
     */
    public String toUrl(String storageKey) {
        String base = stripTrailingSlash(storageProperties.getAssetBaseUrl());
        String prefix = storageProperties.getPublicPrefix();
        return base + prefix + "/" + storageKey;
    }

    private String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
