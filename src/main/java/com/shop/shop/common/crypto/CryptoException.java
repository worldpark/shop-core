package com.shop.shop.common.crypto;

/**
 * 암복호화 처리 중 발생한 오류를 공통 RuntimeException으로 변환한다.
 */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
