package com.shop.shop.e2e.support;

import com.shop.shop.common.crypto.EnvelopeEncryptionUtils;

import java.nio.file.Path;

/**
 * E2E JDBC 시더용 PII 암호화 헬퍼.
 *
 * <p>앱과 동일한 KEK({@code SHOP_CRYPTO_KEK_FILE} 환경변수)로 PII 컬럼 값을 암호화한다.
 * JDBC 시더가 DB에 직접 INSERT할 때 암호화된 값을 넣어야, JPA가 조회 시
 * {@code EncryptedStringConverter}로 올바르게 복호화할 수 있다.
 *
 * <p>KEK 파일이 설정되지 않으면 즉시 {@link IllegalStateException}을 던진다.
 * 평문 기본값을 사용하지 않는다(앱과 KEK 불일치로 동일 버그 재발 방지).
 */
public final class E2ePii {

    private static final String KEK_FILE_ENV = "SHOP_CRYPTO_KEK_FILE";
    private static final Path KEK_FILE = resolveKekFile();

    private E2ePii() {
    }

    /**
     * 평문 PII 문자열을 앱과 동일한 KEK로 암호화해 반환한다.
     *
     * @param plaintext 암호화할 평문 (null 불허)
     * @return {@code v1:iv:wrappedDek:cipher} 형식 암호문
     */
    public static String enc(String plaintext) {
        return EnvelopeEncryptionUtils.encryptWithKekFile(plaintext, KEK_FILE);
    }

    private static Path resolveKekFile() {
        String kekFilePath = System.getenv(KEK_FILE_ENV);
        if (kekFilePath == null || kekFilePath.isBlank()) {
            throw new IllegalStateException(
                    "e2eTest는 실행 중인 앱과 동일한 SHOP_CRYPTO_KEK_FILE 환경변수가 필요합니다. "
                    + "현재 환경변수가 설정되지 않았습니다.");
        }
        return Path.of(kekFilePath);
    }
}
