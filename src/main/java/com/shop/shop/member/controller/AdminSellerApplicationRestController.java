package com.shop.shop.member.controller;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.member.dto.RejectRequest;
import com.shop.shop.member.dto.SellerApplicationSummaryResponse;
import com.shop.shop.member.service.AdminSellerApplicationServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 판매자 신청 REST 컨트롤러 (admin 전용).
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} (008이 이미 존재).
 * SELLER/CONSUMER → 403 JSON, 비인증 → 401 JSON.
 *
 * <p>비즈니스 로직 없음 — {@link AdminSellerApplicationServiceResponse}에 전적으로 위임 (forbidden-rule).
 */
@RestController
@RequestMapping("/api/v1/admin/seller-applications")
@RequiredArgsConstructor
public class AdminSellerApplicationRestController {

    private final AdminSellerApplicationServiceResponse adminSellerApplicationServiceResponse;

    /**
     * 판매자 신청 목록 조회.
     * GET /api/v1/admin/seller-applications?status=&page=&size=
     *
     * @param status status 필터 (null/빈 문자열 = 전체, PENDING/APPROVED/REJECTED)
     * @param page   페이지 번호 (0 기반, 기본값 0)
     * @param size   페이지 크기 (기본값 20)
     * @return 200 + PageResponse&lt;SellerApplicationSummaryResponse&gt;
     */
    @GetMapping
    public ResponseEntity<PageResponse<SellerApplicationSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(adminSellerApplicationServiceResponse.list(status, page, size));
    }

    /**
     * 판매자 신청 승인.
     * POST /api/v1/admin/seller-applications/{id}/approve
     *
     * @param id   신청 ID
     * @param auth 인증 객체 (principal = adminUserId long)
     * @return 200
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable long id,
            Authentication auth) {

        adminSellerApplicationServiceResponse.approve(auth, id);
        return ResponseEntity.ok().build();
    }

    /**
     * 판매자 신청 반려.
     * POST /api/v1/admin/seller-applications/{id}/reject
     *
     * @param id   신청 ID
     * @param req  반려 요청 DTO (@Valid — reason 필수)
     * @param auth 인증 객체 (principal = adminUserId long)
     * @return 200
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable long id,
            @Valid @RequestBody RejectRequest req,
            Authentication auth) {

        adminSellerApplicationServiceResponse.reject(auth, id, req);
        return ResponseEntity.ok().build();
    }
}
