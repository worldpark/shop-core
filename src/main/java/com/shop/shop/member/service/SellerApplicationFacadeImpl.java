package com.shop.shop.member.service;

import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.SellerApplicationStatus;
import com.shop.shop.member.dto.SellerApplicationEligibility;
import com.shop.shop.member.dto.SellerApplicationRequest;
import com.shop.shop.member.dto.SellerApplicationResponse;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.spi.SellerApplicationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link SellerApplicationFacade} 구현체.
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link SellerApplicationFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>email → userId 해석 ({@link MemberService#getByEmail(String)})</li>
 *   <li>현재 role/PENDING 존재 여부 → {@link SellerApplicationEligibility} scalar/DTO 변환 (Role enum 미노출)</li>
 *   <li>{@link com.shop.shop.member.domain.SellerApplication} Entity → {@link SellerApplicationResponse} DTO 매핑</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class SellerApplicationFacadeImpl implements SellerApplicationFacade {

    private final SellerApplicationService sellerApplicationService;
    private final MemberService memberService;
    private final SellerApplicationRepository sellerApplicationRepository;

    /**
     * {@inheritDoc}
     *
     * <p>자격 판정 순서:
     * <ol>
     *   <li>현재 role != CONSUMER → {@code eligible=false, reason="이미 판매자 이상 권한입니다."}</li>
     *   <li>PENDING 신청 존재 → {@code eligible=false, reason="이미 심사 중인 신청이 있습니다."}</li>
     *   <li>else → {@code eligible=true, reason=null}</li>
     * </ol>
     * Role enum을 web에 노출하지 않고 boolean/String만 반환한다.
     */
    @Override
    public SellerApplicationEligibility checkEligibility(String email) {
        var user = memberService.getByEmail(email);

        if (user.getRole() != Role.CONSUMER) {
            return new SellerApplicationEligibility(false, "이미 판매자 이상 권한입니다.");
        }

        if (sellerApplicationRepository.existsByUserIdAndStatus(user.getId(), SellerApplicationStatus.PENDING)) {
            return new SellerApplicationEligibility(false, "이미 심사 중인 신청이 있습니다.");
        }

        return new SellerApplicationEligibility(true, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>email → userId 해석 후 {@link SellerApplicationService#apply}에 위임.
     * 자격(role==CONSUMER, PENDING 미중복)은 서비스가 재검증한다 (409).
     */
    @Override
    public void apply(String email, SellerApplicationRequest req) {
        long userId = memberService.getByEmail(email).getId();
        sellerApplicationService.apply(userId, req);
    }

    /**
     * {@inheritDoc}
     *
     * <p>email → userId 해석 후 {@link SellerApplicationService#findMyLatest}에 위임.
     * 없으면 빈 Optional 반환 (View 안내 화면용 — REST 404와 다름 §1.7).
     */
    @Override
    public Optional<SellerApplicationResponse> findMyApplication(String email) {
        long userId = memberService.getByEmail(email).getId();
        return sellerApplicationService.findMyLatest(userId)
                .map(SellerApplicationResponse::from);
    }
}
