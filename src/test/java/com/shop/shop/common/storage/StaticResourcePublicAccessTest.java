package com.shop.shop.common.storage;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StaticResourceConfig + SecurityConfig View 체인 통합 테스트.
 *
 * <p>plan 5절·Task Test 요구사항: 업로드된 이미지가 {@code /assets/**}로 인증 없이 200 조회됨
 * (ResourceHandler + SecurityConfig permitAll 검증).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>테스트용 storage root 아래에 실제 파일을 만들고 인증 헤더 없이 GET 시 200 서빙됨</li>
 *   <li>존재하지 않는 키(파일 없음) GET 시 404 응답</li>
 * </ul>
 *
 * <p>테스트 종료 후 생성한 파일을 정리한다 (@AfterEach).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class StaticResourcePublicAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StorageProperties storageProperties;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private OptionValueRepository optionValueRepository;

    @MockitoBean
    private ProductVariantRepository productVariantRepository;

    @MockitoBean
    private ProductImageRepository productImageRepository;

    @MockitoBean
    private CartRepository cartRepository;

    @MockitoBean
    private CartItemRepository cartItemRepository;

    private Path createdFile;

    /** 테스트용 storage root 하위에 실제 이미지 파일을 생성한다. */
    @BeforeEach
    void setUp() throws IOException {
        String storageRoot = storageProperties.getRoot();
        Path productDir = Paths.get(storageRoot, "products", "999");
        Files.createDirectories(productDir);

        String filename = UUID.randomUUID() + ".png";
        createdFile = productDir.resolve(filename);
        Files.write(createdFile, "fake-png-content".getBytes());
    }

    /** 테스트에서 생성한 파일을 정리한다. */
    @AfterEach
    void tearDown() throws IOException {
        if (createdFile != null && Files.exists(createdFile)) {
            Files.delete(createdFile);
        }
    }

    @Test
    @DisplayName("/assets/** — 인증 없이 실제 파일 GET 시 200 서빙됨 (ResourceHandler + permitAll 검증)")
    void assets_existingFile_unauthenticated_returns200() throws Exception {
        // createdFile: {storageRoot}/products/999/{uuid}.png
        // storageKey: products/999/{uuid}.png
        String storageRoot = storageProperties.getRoot();
        String storageKey = Paths.get(storageRoot)
                .relativize(createdFile)
                .toString()
                .replace("\\", "/");

        String publicPrefix = storageProperties.getPublicPrefix(); // /assets
        String requestUrl = publicPrefix + "/" + storageKey;

        // 인증 헤더 없이 요청 → 200 서빙
        mockMvc.perform(get(requestUrl))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/assets/** — 존재하지 않는 파일 GET 시 404 (ResourceHandler 동작 고정)")
    void assets_nonExistentFile_returns404() throws Exception {
        String publicPrefix = storageProperties.getPublicPrefix(); // /assets
        String requestUrl = publicPrefix + "/products/999/nonexistent-" + UUID.randomUUID() + ".png";

        // 인증 헤더 없이 요청 → 404 (파일 없음)
        mockMvc.perform(get(requestUrl))
                .andExpect(status().isNotFound());
    }
}
