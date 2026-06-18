package com.shop.shop.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeEncryptionUtilsTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("문자열 암복호화 — KEK로 감싼 DEK를 사용해 원문을 복원한다")
    void encryptAndDecrypt_restoresPlainText() {
        String kek = testKek();
        String plainText = "sensitive-member-data-010-1234-5678";

        String encryptedText = EnvelopeEncryptionUtils.encrypt(plainText, kek);
        String decryptedText = EnvelopeEncryptionUtils.decrypt(encryptedText, kek);

        assertThat(encryptedText).isNotEqualTo(plainText);
        assertThat(encryptedText).startsWith("v1:");
        assertThat(decryptedText).isEqualTo(plainText);
    }

    @Test
    @DisplayName("암호화 비결정성 — 같은 평문과 KEK도 매번 다른 암호문을 만든다")
    void encrypt_samePlainTextProducesDifferentCipherText() {
        String kek = testKek();
        String plainText = "same-sensitive-value";

        String first = EnvelopeEncryptionUtils.encrypt(plainText, kek);
        String second = EnvelopeEncryptionUtils.encrypt(plainText, kek);

        assertThat(first).isNotEqualTo(second);
        assertThat(EnvelopeEncryptionUtils.decrypt(first, kek)).isEqualTo(plainText);
        assertThat(EnvelopeEncryptionUtils.decrypt(second, kek)).isEqualTo(plainText);
    }

    @Test
    @DisplayName("바이트 배열 암복호화 — 원본 바이트를 그대로 복원한다")
    void encryptBytesAndDecryptBytes_restoresOriginalBytes() {
        String kek = testKek();
        byte[] plainBytes = "binary-like-value".getBytes(StandardCharsets.UTF_8);

        String encryptedText = EnvelopeEncryptionUtils.encrypt(plainBytes, kek);
        byte[] decryptedBytes = EnvelopeEncryptionUtils.decryptToBytes(encryptedText, kek);

        assertThat(decryptedBytes).isEqualTo(plainBytes);
    }

    @Test
    @DisplayName("KEK 파일 암복호화 — 파일 경로에서 Base64 KEK를 읽어 원문을 복원한다")
    void encryptAndDecryptWithKekFile_restoresPlainText() throws Exception {
        Path kekFile = tempDir.resolve("shop-core-kek.txt");
        Files.writeString(kekFile, testKek(), StandardCharsets.UTF_8);
        String plainText = "sensitive-address";

        String encryptedText = EnvelopeEncryptionUtils.encryptWithKekFile(plainText, kekFile);
        String decryptedText = EnvelopeEncryptionUtils.decryptWithKekFile(encryptedText, kekFile);

        assertThat(encryptedText).startsWith("v1:");
        assertThat(decryptedText).isEqualTo(plainText);
    }

    @Test
    @DisplayName("다른 KEK 복호화 — 인증 태그 검증 실패를 CryptoException으로 변환한다")
    void decryptWithDifferentKek_throwsCryptoException() {
        String encryptionKek = testKek();
        String otherKek = testKek();
        String encryptedText = EnvelopeEncryptionUtils.encrypt("secret", encryptionKek);

        assertThatThrownBy(() -> EnvelopeEncryptionUtils.decrypt(encryptedText, otherKek))
                .isInstanceOf(CryptoException.class)
                .hasMessage("복호화에 실패했습니다.");
    }

    @Test
    @DisplayName("잘못된 암호문 형식 — CryptoException을 던진다")
    void decryptMalformedToken_throwsCryptoException() {
        String kek = testKek();

        assertThatThrownBy(() -> EnvelopeEncryptionUtils.decrypt("invalid-token", kek))
                .isInstanceOf(CryptoException.class)
                .hasMessage("지원하지 않는 암호문 형식입니다.");
    }

    @Test
    @DisplayName("잘못된 KEK — Base64 AES 키가 아니면 CryptoException을 던진다")
    void invalidKek_throwsCryptoException() {
        assertThatThrownBy(() -> EnvelopeEncryptionUtils.encrypt("secret", "not-base64"))
                .isInstanceOf(CryptoException.class)
                .hasMessage("KEK는 Base64로 인코딩된 AES 키여야 합니다.");
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
}
