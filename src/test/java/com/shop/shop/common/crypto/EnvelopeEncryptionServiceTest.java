package com.shop.shop.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import javax.crypto.KeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeEncryptionServiceTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("shop.crypto.kek-file — 설정된 파일 경로의 KEK로 암복호화한다")
    void encryptAndDecrypt_usesConfiguredKekFile() throws Exception {
        Path kekFile = tempDir.resolve("shop-core-kek.txt");
        Files.writeString(kekFile, testKek(), StandardCharsets.UTF_8);

        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues("shop.crypto.kek-file=" + kekFile)
                .run(context -> {
                    EnvelopeEncryptionService service = context.getBean(EnvelopeEncryptionService.class);

                    String encryptedText = service.encrypt("secret-value");
                    String decryptedText = service.decrypt(encryptedText);

                    assertThat(encryptedText).startsWith("v1:");
                    assertThat(decryptedText).isEqualTo("secret-value");
                });
    }

    private String testKek() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256, SECURE_RANDOM);
            return Base64.getEncoder().encodeToString(keyGenerator.generateKey().getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("테스트 KEK 생성에 실패했습니다.", e);
        }
    }

    @EnableConfigurationProperties(CryptoProperties.class)
    static class TestConfig {

        @Bean
        EnvelopeEncryptionService envelopeEncryptionService(CryptoProperties properties) {
            return new EnvelopeEncryptionService(properties);
        }
    }
}
