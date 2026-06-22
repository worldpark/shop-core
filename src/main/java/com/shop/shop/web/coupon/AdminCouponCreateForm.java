package com.shop.shop.web.coupon;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 관리자 쿠폰 정의 생성 폼 백킹 객체 (View 전용).
 *
 * <p>Entity 직접 바인딩 금지. 검증 어노테이션은 1차 UX 검증.
 * discountType 화이트리스트(fixed/percent)·value 양수·endsAt이 startsAt 초과의 최종 권위는
 * 031 Coupon.createDefinition(도메인 검증 + DB CHECK) — facade가 BusinessException으로 최종 거부.
 *
 * <p>datetime-local 바인딩: HTML {@code <input type="datetime-local">}의 값이
 * {@code yyyy-MM-ddTHH:mm} 형식으로 전달된다.
 * Spring {@code ConversionService}가 {@link java.time.Instant}로 변환하도록
 * controller에서 직접 변환하거나 별도 converter를 사용한다.
 * 본 폼에서는 controller가 String → Instant 변환을 담당하므로 필드는 String으로 선언한다.
 *
 * <p>usageLimit: 빈 문자열 전달 시 null(무제한)로 처리한다.
 * isActive: 미체크 시 false.
 */
@Getter
@Setter
public class AdminCouponCreateForm {

    @NotBlank(message = "쿠폰 코드를 입력해 주세요.")
    private String code;

    @NotBlank(message = "쿠폰 이름을 입력해 주세요.")
    private String name;

    @NotBlank(message = "할인 유형을 선택해 주세요.")
    private String discountType;

    @NotNull(message = "할인 값을 입력해 주세요.")
    @Positive(message = "할인 값은 0보다 커야 합니다.")
    private BigDecimal value;

    private BigDecimal minOrderAmount;

    private BigDecimal maxDiscount;

    /**
     * datetime-local 입력값 (yyyy-MM-ddTHH:mm). 컨트롤러에서 Instant로 변환.
     */
    @NotBlank(message = "시작일을 입력해 주세요.")
    private String startsAt;

    /**
     * datetime-local 입력값 (yyyy-MM-ddTHH:mm). 컨트롤러에서 Instant로 변환.
     */
    @NotBlank(message = "종료일을 입력해 주세요.")
    private String endsAt;

    /**
     * 사용 한도. 빈 값 = null(무제한).
     */
    private Integer usageLimit;

    /**
     * 활성 여부. 체크박스 미체크 시 false.
     */
    private Boolean isActive;

    public boolean getIsActive() {
        return Boolean.TRUE.equals(isActive);
    }

    /**
     * startsAt 문자열(datetime-local)을 UTC Instant로 변환한다.
     * HTML datetime-local은 브라우저 로컬 시간(KST)으로 입력되므로 KST 오프셋(+09:00)을 적용한다.
     */
    public Instant parseStartsAt() {
        return parseLocalDateTime(startsAt);
    }

    /**
     * endsAt 문자열(datetime-local)을 UTC Instant로 변환한다.
     */
    public Instant parseEndsAt() {
        return parseLocalDateTime(endsAt);
    }

    private static Instant parseLocalDateTime(String localDateTimeStr) {
        if (localDateTimeStr == null || localDateTimeStr.isBlank()) {
            return null;
        }
        // datetime-local 형식: "yyyy-MM-ddTHH:mm" (초 없음) 또는 "yyyy-MM-ddTHH:mm:ss"
        // KST(+09:00) 기준으로 파싱해 Instant(UTC) 반환
        java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                localDateTimeStr.length() == 16 ? localDateTimeStr + ":00" : localDateTimeStr
        );
        return ldt.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    }
}
