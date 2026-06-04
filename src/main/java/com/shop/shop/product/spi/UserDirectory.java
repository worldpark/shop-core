package com.shop.shop.product.spi;

/**
 * product 모듈의 사용자 디렉터리 조회 포트 (SPI — published port).
 *
 * <p>product가 소유하는 인터페이스. "사용자 디렉터리 조회"를 추상화한 포트다.
 * 구현은 product 밖(member.adapter.MemberUserDirectoryAdapter)에 있고,
 * product는 이 인터페이스만 의존한다.
 *
 * <p>의존 방향: member → product.spi (단방향).
 * product는 member를 전혀 참조하지 않는다.
 *
 * <p>REST 진입점은 principal=userId(long)라 이 포트를 사용하지 않는다.
 * View(form-login) 진입점만 email→userId 변환이 필요해 이 포트를 사용한다.
 *
 * @see com.shop.shop.product.controller.SellerProductViewController
 */
public interface UserDirectory {

    /**
     * 인증된 세션의 email로 userId 조회.
     *
     * <p>인증 세션의 email은 항상 존재하는 사용자라고 가정한다.
     * 미존재 시 구현이 {@link IllegalStateException}(시스템 불변식 위반 — 클라이언트 입력 오류 아님)을 던진다.
     *
     * @param email 인증 세션의 email (not null)
     * @return userId
     * @throws IllegalStateException email에 해당하는 사용자가 없음(인증 세션-회원 디렉터리 불일치)
     */
    long findUserIdByEmail(String email);
}
