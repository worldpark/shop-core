package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 상품당 이미지 개수 상한 초과 시 발생하는 예외 (400 Bad Request).
 *
 * <p>upload() 에서 storage.put 호출 이전에 검사하며, 상한에 도달한 경우 파일을 저장하지 않고 거부한다.
 */
public class ImageLimitExceededException extends BusinessException {

    public ImageLimitExceededException(int maxImagesPerProduct) {
        super("상품당 이미지는 최대 " + maxImagesPerProduct + "장까지 등록할 수 있습니다.", HttpStatus.BAD_REQUEST);
    }
}
