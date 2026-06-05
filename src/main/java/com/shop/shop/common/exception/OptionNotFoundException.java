package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 옵션을 찾을 수 없을 때 발생하는 예외 (404 Not Found).
 *
 * <p>V10 불변식: optionId가 해당 productId 하위 리소스가 아닌 경우 포함.
 */
public class OptionNotFoundException extends BusinessException {

    public OptionNotFoundException(long id) {
        super("옵션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
