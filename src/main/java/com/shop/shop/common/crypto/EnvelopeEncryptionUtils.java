package com.shop.shop.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * KEK(Key Encryption Key) 기반 양방향 암복호화 유틸.
 *
 * <p>각 암호화마다 새 DEK(Data Encryption Key)를 생성해 평문을 AES-GCM으로 암호화하고,
 * DEK는 KEK로 AES Key Wrap 처리해 암호문에 함께 담는다.
 */
public final class EnvelopeEncryptionUtils {

    private static final String VERSION = "v1";
    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String AES_WRAP = "AESWrap";
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String TOKEN_SEPARATOR = ":";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private EnvelopeEncryptionUtils() {
    }

    /**
     * 문자열 평문을 암호화한다.
     *
     * @param plainText 암호화할 문자열
     * @param base64Kek 외부 Secret/KMS/Vault 등에서 주입받은 Base64 AES KEK
     * @return 저장 가능한 문자열 암호문
     */
    public static String encrypt(String plainText, String base64Kek) {
        Objects.requireNonNull(plainText, "plainText must not be null");
        return encrypt(plainText.getBytes(StandardCharsets.UTF_8), base64Kek);
    }

    /**
     * 문자열 암호문을 복호화한다.
     *
     * @param encryptedText {@link #encrypt(String, String)}가 반환한 암호문
     * @param base64Kek 암호화에 사용한 Base64 KEK
     * @return 복호화된 문자열
     */
    public static String decrypt(String encryptedText, String base64Kek) {
        return new String(decryptToBytes(encryptedText, base64Kek), StandardCharsets.UTF_8);
    }

    /**
     * KEK 파일 경로에서 Base64 KEK를 읽어 문자열 평문을 암호화한다.
     */
    public static String encryptWithKekFile(String plainText, Path kekFile) {
        return encrypt(plainText, readKek(kekFile));
    }

    /**
     * KEK 파일 경로에서 Base64 KEK를 읽어 문자열 암호문을 복호화한다.
     */
    public static String decryptWithKekFile(String encryptedText, Path kekFile) {
        return decrypt(encryptedText, readKek(kekFile));
    }

    /**
     * KEK 파일 경로에서 Base64 KEK를 읽어 바이트 배열 평문을 암호화한다.
     */
    public static String encryptWithKekFile(byte[] plainBytes, Path kekFile) {
        return encrypt(plainBytes, readKek(kekFile));
    }

    /**
     * KEK 파일 경로에서 Base64 KEK를 읽어 문자열 암호문을 바이트 배열로 복호화한다.
     */
    public static byte[] decryptToBytesWithKekFile(String encryptedText, Path kekFile) {
        return decryptToBytes(encryptedText, readKek(kekFile));
    }

    /**
     * 바이트 배열 평문을 암호화한다.
     */
    public static String encrypt(byte[] plainBytes, String base64Kek) {
        Objects.requireNonNull(plainBytes, "plainBytes must not be null");

        try {
            SecretKey kek = decodeKek(base64Kek);
            SecretKey dek = generateAesKey();
            byte[] iv = randomIv();
            byte[] cipherBytes = encryptWithDek(plainBytes, dek, iv);
            byte[] wrappedDek = wrapDek(dek, kek);

            return String.join(TOKEN_SEPARATOR,
                    VERSION,
                    TOKEN_ENCODER.encodeToString(iv),
                    TOKEN_ENCODER.encodeToString(wrappedDek),
                    TOKEN_ENCODER.encodeToString(cipherBytes));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("암호화에 실패했습니다.", e);
        }
    }

    /**
     * 문자열 암호문을 바이트 배열로 복호화한다.
     */
    public static byte[] decryptToBytes(String encryptedText, String base64Kek) {
        Objects.requireNonNull(encryptedText, "encryptedText must not be null");

        try {
            String[] parts = encryptedText.split(TOKEN_SEPARATOR, -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw new CryptoException("지원하지 않는 암호문 형식입니다.");
            }

            SecretKey kek = decodeKek(base64Kek);
            byte[] iv = TOKEN_DECODER.decode(parts[1]);
            byte[] wrappedDek = TOKEN_DECODER.decode(parts[2]);
            byte[] cipherBytes = TOKEN_DECODER.decode(parts[3]);
            SecretKey dek = unwrapDek(wrappedDek, kek);

            return decryptWithDek(cipherBytes, dek, iv);
        } catch (CryptoException e) {
            throw e;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("복호화에 실패했습니다.", e);
        }
    }

    private static String readKek(Path kekFile) {
        Objects.requireNonNull(kekFile, "kekFile must not be null");

        try {
            String base64Kek = Files.readString(kekFile, StandardCharsets.UTF_8).trim();
            decodeKek(base64Kek);
            return base64Kek;
        } catch (IOException e) {
            throw new CryptoException("KEK 파일을 읽을 수 없습니다.", e);
        }
    }

    private static SecretKey decodeKek(String base64Kek) {
        Objects.requireNonNull(base64Kek, "base64Kek must not be null");

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Kek);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("KEK는 Base64로 인코딩된 AES 키여야 합니다.", e);
        }

        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new CryptoException("KEK는 AES-128, AES-192 또는 AES-256 길이여야 합니다.");
        }
        return new SecretKeySpec(keyBytes, AES);
    }

    private static SecretKey generateAesKey() throws GeneralSecurityException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(AES);
        keyGenerator.init(AES_KEY_BITS, SECURE_RANDOM);
        return keyGenerator.generateKey();
    }

    private static byte[] randomIv() {
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    private static byte[] encryptWithDek(byte[] plainBytes, SecretKey dek, byte[] iv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plainBytes);
    }

    private static byte[] decryptWithDek(byte[] cipherBytes, SecretKey dek, byte[] iv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(cipherBytes);
    }

    private static byte[] wrapDek(SecretKey dek, SecretKey kek) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_WRAP);
        cipher.init(Cipher.WRAP_MODE, kek);
        return cipher.wrap(dek);
    }

    private static SecretKey unwrapDek(byte[] wrappedDek, SecretKey kek) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_WRAP);
        cipher.init(Cipher.UNWRAP_MODE, kek);
        return (SecretKey) cipher.unwrap(wrappedDek, AES, Cipher.SECRET_KEY);
    }
}
