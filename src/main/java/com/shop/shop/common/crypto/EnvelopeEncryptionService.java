package com.shop.shop.common.crypto;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 애플리케이션 설정의 KEK 파일을 사용하는 양방향 암복호화 컴포넌트.
 */
@Component
public class EnvelopeEncryptionService {

    private final CryptoProperties properties;

    public EnvelopeEncryptionService(CryptoProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String plainText) {
        return EnvelopeEncryptionUtils.encryptWithKekFile(plainText, kekFile());
    }

    public String decrypt(String encryptedText) {
        return EnvelopeEncryptionUtils.decryptWithKekFile(encryptedText, kekFile());
    }

    public String encrypt(byte[] plainBytes) {
        return EnvelopeEncryptionUtils.encryptWithKekFile(plainBytes, kekFile());
    }

    public byte[] decryptToBytes(String encryptedText) {
        return EnvelopeEncryptionUtils.decryptToBytesWithKekFile(encryptedText, kekFile());
    }

    private java.nio.file.Path kekFile() {
        return Objects.requireNonNull(properties.kekFile(), "shop.crypto.kek-file must not be null");
    }
}
