package com.shop.shop.member.spi;

/**
 * email → userId 조회 published port (member 소유).
 *
 * <p>cart 모듈의 View facade가 form-login principal(email)을 userId로 변환할 때 사용한다.
 * member Entity를 노출하지 않는다 — scalar userId(long)만 반환.
 *
 * <p>product.spi.UserDirectory와 별개: product는 product 소유 포트(판매자 소유권),
 * MemberDirectory는 member 소유 포트(일반 회원 조회). cart가 UserDirectory를 재사용하면
 * 모듈 소유 경계가 흐려지므로 분리한다(Task 명시).
 *
 * <p>의존 방향: cart → member.spi 단방향. member는 cart를 참조하지 않는다.
 */
public interface MemberDirectory {

    /**
     * 이메일로 userId 조회.
     *
     * <p>cart의 View facade가 form-login email을 userId로 해석할 때 사용한다.
     * 인증 세션의 email은 항상 존재하는 사용자라고 가정한다.
     * 미존재 시 {@link IllegalStateException}(인증 세션-디렉터리 불일치, 운영 이상)을 던진다.
     *
     * @param email form-login principal email
     * @return userId
     * @throws IllegalStateException email에 해당하는 사용자 없음 (시스템 불변식 위반)
     */
    long findUserIdByEmail(String email);
}
