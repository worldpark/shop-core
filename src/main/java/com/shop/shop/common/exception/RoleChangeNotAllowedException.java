package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * role 변경 불변식 위반 시 발생하는 예외.
 *
 * <p>사유별 정적 팩토리:
 * <ul>
 *   <li>{@link #forbiddenPromotion()} — ADMIN 승격 시도: 400 Bad Request</li>
 *   <li>{@link #selfDemotion()} — 본인 ADMIN 강등 시도: 409 Conflict</li>
 *   <li>{@link #lastAdmin()} — 마지막 ADMIN 강등 시도: 409 Conflict</li>
 * </ul>
 */
public class RoleChangeNotAllowedException extends BusinessException {

    private RoleChangeNotAllowedException(String message, HttpStatus status) {
        super(message, status);
    }

    /**
     * ADMIN 승격 요청 — ADMIN 권한으로의 변경은 허용되지 않는다.
     * HTTP 400 Bad Request.
     */
    public static RoleChangeNotAllowedException forbiddenPromotion() {
        return new RoleChangeNotAllowedException(
                "ADMIN 권한으로의 변경은 허용되지 않습니다.",
                HttpStatus.BAD_REQUEST
        );
    }

    /**
     * 본인 ADMIN 강등 시도 — 본인의 ADMIN 권한은 변경할 수 없다.
     * HTTP 409 Conflict.
     */
    public static RoleChangeNotAllowedException selfDemotion() {
        return new RoleChangeNotAllowedException(
                "본인의 ADMIN 권한은 변경할 수 없습니다.",
                HttpStatus.CONFLICT
        );
    }

    /**
     * 마지막 ADMIN 강등 시도 — 마지막 ADMIN 권한은 변경할 수 없다.
     * HTTP 409 Conflict.
     */
    public static RoleChangeNotAllowedException lastAdmin() {
        return new RoleChangeNotAllowedException(
                "마지막 ADMIN 권한은 변경할 수 없습니다.",
                HttpStatus.CONFLICT
        );
    }
}
