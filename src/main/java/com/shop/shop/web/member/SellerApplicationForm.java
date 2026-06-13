package com.shop.shop.web.member;

import com.shop.shop.member.dto.SellerApplicationRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 판매자 신청 View 폼 (가변 class — @ModelAttribute 바인딩).
 *
 * <p>web 레이어 소유. {@link SellerApplicationRequest} record와 별개로 존재한다.
 * Thymeleaf 폼 바인딩을 위해 Setter가 필요하므로 record가 아닌 가변 class로 정의한다.
 * {@link #toRequest()} 메서드로 member 도메인 DTO로 변환한다 (web 책임 — architecture-rule).
 */
@Getter
@Setter
@NoArgsConstructor
public class SellerApplicationForm {

    @NotBlank(message = "상호명은 필수입니다.")
    private String businessName;

    @NotBlank(message = "사업자등록번호는 필수입니다.")
    @Pattern(regexp = "\\d{10}", message = "사업자등록번호는 숫자 10자리입니다.")
    private String businessRegistrationNumber;

    @NotBlank(message = "담당자 연락처는 필수입니다.")
    private String contactPhone;

    /**
     * View 폼 → member 도메인 요청 DTO 변환.
     * web→member.spi 포트 경유 시 facade가 DTO를 받으므로 web이 변환을 담당한다
     * (architecture-rule: "web 폼 → 포트 DTO 변환은 호출자(web) 책임").
     *
     * @return SellerApplicationRequest
     */
    public SellerApplicationRequest toRequest() {
        return new SellerApplicationRequest(businessName, businessRegistrationNumber, contactPhone);
    }
}
