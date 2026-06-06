package com.shop.shop.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AssetUrlResolver 단위 테스트.
 *
 * <p>URL 합성의 단일 책임 지점 검증.
 */
class AssetUrlResolverTest {

    @Test
    @DisplayName("toUrl — assetBaseUrl + publicPrefix + storageKey 합성")
    void toUrl_composesCorrectUrl() {
        StorageProperties props = propsOf("http://localhost:8080", "/assets");
        AssetUrlResolver resolver = new AssetUrlResolver(props);

        String url = resolver.toUrl("products/10/uuid.jpg");

        assertThat(url).isEqualTo("http://localhost:8080/assets/products/10/uuid.jpg");
    }

    @Test
    @DisplayName("toUrl — assetBaseUrl 끝에 슬래시가 있어도 중복 슬래시 없음")
    void toUrl_trailingSlashInBaseUrl_noDoubleSlash() {
        StorageProperties props = propsOf("http://localhost:8080/", "/assets");
        AssetUrlResolver resolver = new AssetUrlResolver(props);

        String url = resolver.toUrl("products/10/uuid.jpg");

        assertThat(url).isEqualTo("http://localhost:8080/assets/products/10/uuid.jpg");
        assertThat(url).doesNotContain("//assets");
    }

    @Test
    @DisplayName("toUrl — 다른 storageKey는 다른 URL 반환")
    void toUrl_differentStorageKeys_differentUrls() {
        StorageProperties props = propsOf("http://localhost:8080", "/assets");
        AssetUrlResolver resolver = new AssetUrlResolver(props);

        String url1 = resolver.toUrl("products/10/uuid1.jpg");
        String url2 = resolver.toUrl("products/10/uuid2.jpg");

        assertThat(url1).isNotEqualTo(url2);
    }

    private StorageProperties propsOf(String assetBaseUrl, String publicPrefix) {
        StorageProperties props = new StorageProperties();
        props.setRoot("/tmp/uploads");
        props.setAssetBaseUrl(assetBaseUrl);
        props.setPublicPrefix(publicPrefix);
        props.setAllowedExtensions(List.of("jpg", "jpeg", "png", "gif", "webp"));
        return props;
    }
}
