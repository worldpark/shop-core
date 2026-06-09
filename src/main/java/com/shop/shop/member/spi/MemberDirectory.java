package com.shop.shop.member.spi;

/**
 * member 조회 published port (member 소유).
 *
 * <p>email → userId 조회, userId → 연락처 조회를 제공한다.
 * member Entity를 노출하지 않는다 — scalar/DTO만 반환.
 *
 * <p>product.spi.UserDirectory와 별개: product는 product 소유 포트(판매자 소유권),
 * MemberDirectory는 member 소유 포트(일반 회원 조회). cart가 UserDirectory를 재사용하면
 * 모듈 소유 경계가 흐려지므로 분리한다(Task 명시).
 *
 * <p>의존 방향: cart/order/payment → member.spi 단방향. member는 이들을 참조하지 않는다.
 */
public interface MemberDirectory {

    /**
     * 이메일로 userId 조회.
     *
     * <p>View facade가 form-login email을 userId로 해석할 때 사용한다.
     * 인증 세션의 email은 항상 존재하는 사용자라고 가정한다.
     * 미존재 시 {@link IllegalStateException}(인증 세션-디렉터리 불일치, 운영 이상)을 던진다.
     *
     * @param email form-login principal email
     * @return userId
     * @throws IllegalStateException email에 해당하는 사용자 없음 (시스템 불변식 위반)
     */
    long findUserIdByEmail(String email);

    /**
     * userId로 연락처 조회 (이벤트 페이로드용).
     *
     * <p>order 모듈이 OrderCompletedEvent 페이로드에 memberEmail/memberName을 채울 때 사용한다.
     * member Entity를 노출하지 않는다 — scalar DTO({@link MemberContact})만 반환.
     *
     * <p>인증된 사용자의 주문이므로 userId는 항상 존재해야 한다.
     * 미존재 시 {@link IllegalStateException}(시스템 불변식 위반)을 던진다.
     *
     * @param userId 조회할 회원 ID
     * @return 회원 연락처 (email, name)
     * @throws IllegalStateException userId에 해당하는 사용자 없음 (시스템 불변식 위반)
     */
    MemberContact findContactByUserId(long userId);

    /**
     * 이벤트 페이로드용 회원 연락처 (Entity 미노출, scalar only).
     *
     * @param email 이메일
     * @param name  이름
     */
    record MemberContact(String email, String name) {}
}
