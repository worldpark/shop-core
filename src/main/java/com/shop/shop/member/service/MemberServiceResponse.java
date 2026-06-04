package com.shop.shop.member.service;

import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.MeResponse;
import com.shop.shop.member.dto.SignupRequest;
import com.shop.shop.member.dto.SignupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 회원 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — 하위 {@link MemberService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다 (Constraint).
 *
 * <p>레이어: MemberRestController → MemberServiceResponse → MemberService → MemberRepository
 */
@Service
@RequiredArgsConstructor
public class MemberServiceResponse {

    private final MemberService memberService;

    /**
     * 회원가입 처리 — REST 전용.
     *
     * <p>SignupRequest를 분해해 MemberService.signup에 위임하고 SignupResponse로 변환.
     * password/passwordHash는 응답에 포함하지 않는다 (Constraint).
     *
     * @param request 회원가입 요청 DTO
     * @return SignupResponse (memberId/email/name/role, 비번 제외)
     */
    public SignupResponse signup(SignupRequest request) {
        User user = memberService.signup(
                request.email(),
                request.password(),
                request.name(),
                request.phone()
        );
        return SignupResponse.from(user);
    }

    /**
     * 내 정보 조회 — REST 전용.
     *
     * <p>SecurityContext의 Authentication에서 userId 추출 → User 조회 → MeResponse 반환.
     * principal = userId(long) — 006 JwtAuthenticationFilter 규약과 동일 (AuthServiceResponse.me 패턴 계승).
     *
     * @param authentication SecurityContext의 인증 객체
     * @return MeResponse (id/email/name/role)
     */
    public MeResponse me(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        User user = memberService.getById(userId);
        return MeResponse.from(user);
    }
}
