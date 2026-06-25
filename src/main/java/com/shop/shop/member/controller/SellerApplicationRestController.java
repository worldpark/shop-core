package com.shop.shop.member.controller;

import com.shop.shop.member.dto.SellerApplicationRequest;
import com.shop.shop.member.dto.SellerApplicationResponse;
import com.shop.shop.member.service.SellerApplicationServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 신청 REST 컨트롤러 (consumer 전용).
 *
 * <p>인가: SecurityConfig REST 체인에서 {@code /api/v1/seller-applications/**} → authenticated.
 * 비인증 → 401 JSON (RestAuthenticationEntryPoint).
 * 신청 자격(CONSUMER만) 검증은 서비스 레이어에서 수행 → 409 (보안 403 아님).
 *
 * <p>비즈니스 로직 없음 — {@link SellerApplicationServiceResponse}에 전적으로 위임 (forbidden-rule).
 */
@Tag(name = "seller-application", description = "판매자 신청 — 제출·내 상태 조회")
@RestController
@RequestMapping("/api/v1/seller-applications")
@RequiredArgsConstructor
public class SellerApplicationRestController {

    private final SellerApplicationServiceResponse sellerApplicationServiceResponse;

    /**
     * 판매자 신청 제출.
     * POST /api/v1/seller-applications
     *
     * @param req  신청 요청 DTO (@Valid — 사업자 정보 Bean Validation)
     * @param auth 인증 객체 (principal = userId long)
     * @return 201 Created + 신청 응답 DTO
     */
    @Operation(summary = "판매자 신청 제출")
    @PostMapping
    public ResponseEntity<SellerApplicationResponse> create(
            @Valid @RequestBody SellerApplicationRequest req,
            Authentication auth) {

        SellerApplicationResponse response = sellerApplicationServiceResponse.apply(auth, req);
        return ResponseEntity.status(201).body(response);
    }

    /**
     * 내 신청 상태 조회.
     * GET /api/v1/seller-applications/me
     *
     * <p>이력 없으면 서비스가 404를 throw (§1.7 REST=404 정책).
     * 승격된 SELLER도 본인 APPROVED 이력 조회 가능 (role 자격 제한 없음, 소유권만).
     *
     * @param auth 인증 객체 (principal = userId long)
     * @return 200 + 신청 응답 DTO
     */
    @GetMapping("/me")
    public ResponseEntity<SellerApplicationResponse> me(Authentication auth) {
        return ResponseEntity.ok(sellerApplicationServiceResponse.me(auth));
    }
}
