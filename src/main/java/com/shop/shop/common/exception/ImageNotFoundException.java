package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 상품 이미지를 찾을 수 없을 때 발생하는 예외 (404 Not Found).
 *
 * <p>imageId가 해당 productId 하위 리소스가 아닌 경우를 포함한다.
 * variant의 {@link VariantNotFoundException} 패턴을 따른다.
 */
public class ImageNotFoundException extends BusinessException {

    public ImageNotFoundException(long id) {
        super("이미지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
