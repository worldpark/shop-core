package com.shop.shop.common.crypto;

import org.junit.jupiter.api.BeforeEach;
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

/**
 * {@link EncryptedStringConverter} 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>라운드트립 — 평문 → DB 암호문 → 평문 복원</li>
 *   <li>null 통과 — null 입력 시 null 반환(nullable 컬럼 대응)</li>
 *   <li>암호문 특성 — DB 값이 평문과 다르고 "v1:"으로 시작</li>
 * </ul>
 *
 * <p>KEK는 TempDir에 생성된 임시 파일로 격리(기존 EnvelopeEncryptionServiceTest 패턴 재사용).
 * Spring 컨텍스트는 ApplicationContextRunner로 경량 기동.
 */
class EncryptedStringConverterTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @TempDir
    Path tempDir;

    private EncryptedStringConverter converter;

    @BeforeEach
    void setUp() throws Exception {
        Path kekFile = tempDir.resolve("test-kek.b64");
        Files.writeString(kekFile, generateTestKek(), StandardCharsets.UTF_8);

        // ApplicationContextRunner로 경량 컨텍스트 기동 — 실제 Spring 빈 주입 경로 검증
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues("shop.crypto.kek-file=" + kekFile);

        runner.run(context -> {
            converter = context.getBean(EncryptedStringConverter.class);
        });
    }

    @Test
    @DisplayName("라운드트립 — 평문을 암호화 후 복호화하면 원문을 복원한다")
    void roundTrip_encryptThenDecrypt_restoresPlainText() {
        String plain = "홍길동";

        String dbValue = converter.convertToDatabaseColumn(plain);
        String restored = converter.convertToEntityAttribute(dbValue);

        assertThat(restored).isEqualTo(plain);
    }

    @Test
    @DisplayName("암호문은 평문과 다르고 v1:으로 시작한다")
    void convertToDatabaseColumn_returnsEncryptedValue_differentFromPlain() {
        String plain = "010-1234-5678";

        String dbValue = converter.convertToDatabaseColumn(plain);

        assertThat(dbValue).isNotEqualTo(plain);
        assertThat(dbValue).startsWith("v1:");
    }

    @Test
    @DisplayName("null 평문 → null 암호문 (nullable 컬럼 대응)")
    void convertToDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("null 암호문 → null 평문 (nullable 컬럼 대응)")
    void convertToEntityAttribute_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("비결정성 — 같은 평문도 매번 다른 암호문을 생성한다")
    void convertToDatabaseColumn_sameInput_differentCipherEachTime() {
        String plain = "서울시 강남구";

        String first = converter.convertToDatabaseColumn(plain);
        String second = converter.convertToDatabaseColumn(plain);

        assertThat(first).isNotEqualTo(second);
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo(plain);
    }

    private String generateTestKek() {
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

        @Bean
        EncryptedStringConverter encryptedStringConverter(EnvelopeEncryptionService service) {
            return new EncryptedStringConverter(service);
        }
    }
}
