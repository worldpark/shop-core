package com.shop.shop.web.product;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 업로드 폼 백킹 객체.
 *
 * <p>가변 POJO — Spring MVC @ModelAttribute 데이터 바인딩에 최적화.
 * OptionForm / VariantForm 스타일 계승.
 *
 * <p>도메인 타입 import 금지. 파일 타입 검증은 ProductImageService 단일 지점에서 수행한다
 * (확장자 화이트리스트 + Content-Type). 여기서는 null 여부만 확인한다.
 */
@Getter
@Setter
@NoArgsConstructor
public class ImageUploadForm {

    @NotNull(message = "업로드할 파일을 선택해주세요.")
    private MultipartFile file;
}
