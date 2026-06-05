package com.shop.shop.member.spi;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.dto.MemberSummaryResponse;
import org.springframework.data.domain.Page;

/**
 * 관리자 회원 관리 View 전용 facade (published port).
 *
 * <p>web 모듈의 AdminMemberViewController가 member 도메인 내부 Service·Entity를 직접
 * 참조하지 않도록 이 facade를 경유한다. 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>facade 구현은 내부에서 String → Role 변환, email → userId 해석, User Entity → DTO 변환을
 * 담당한다. web은 String 타입만 전달하므로 도메인 enum({@code Role})을 컴파일타임에 참조하지 않는다.
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 */
public interface AdminMemberFacade {

    /**
     * 관리자 회원 검색.
     *
     * <p>keyword(email/name 부분일치) + role 필터 + 페이지네이션.
     * Entity를 반환하지 않고 {@link MemberSummaryResponse} DTO 페이지를 반환한다.
     *
     * @param keyword 검색 키워드 (이메일 또는 이름, null/빈 문자열 = 전체)
     * @param role    권한 필터 문자열 (null/빈 문자열 = 전체, 그 외 = ADMIN/SELLER/CONSUMER)
     * @param page    페이지 번호 (0 기반)
     * @param size    페이지 크기
     * @return 조건에 맞는 {@link MemberSummaryResponse} DTO 페이지
     */
    Page<MemberSummaryResponse> searchMembers(String keyword, String role, int page, int size);

    /**
     * 관리자 회원 권한 변경.
     *
     * <p>adminEmail을 userId로 해석하고, role(String)을 도메인 enum으로 변환한 후 위임한다.
     * 실패 시 {@link BusinessException}(common — OPEN 모듈)을 전파한다.
     *
     * @param adminEmail     행위 관리자 이메일 (form login session principal — email)
     * @param targetMemberId 변경 대상 회원 ID
     * @param role           변경할 권한 문자열 (SELLER 또는 CONSUMER)
     * @throws BusinessException 권한 변경 불변식 위반 (ADMIN 승격, 자기 강등, 마지막 ADMIN 강등 금지 등)
     */
    void changeRole(String adminEmail, long targetMemberId, String role);
}
