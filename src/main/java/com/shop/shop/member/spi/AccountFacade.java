package com.shop.shop.member.spi;

import com.shop.shop.member.dto.AccountInfo;

/**
 * 계정 self-service View 전용 facade (published port).
 *
 * <p>web 모듈의 AccountViewController가 member 도메인 내부 Service·Entity를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>facade 시그니처는 web 타입(폼 객체)을 파라미터·반환 타입으로 받지 않는다.
 * scalar(String)/DTO(AccountInfo)만 노출하고, web 폼 → scalar 변환은 ViewController 책임이다
 * (architecture-rule: published API가 web을 역참조하지 않게 한다).
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 */
public interface AccountFacade {

    /**
     * 계정 정보 조회 (표시용).
     *
     * <p>email/name/phone scalar만 반환 — 비밀번호 해시·role·토큰·Redis 상태 비노출.
     *
     * @param email 조회할 회원 이메일 (formLogin session principal)
     * @return 표시용 계정 정보 DTO
     */
    AccountInfo getAccountInfo(String email);

    /**
     * 비밀번호 변경.
     *
     * <p>email → userId 해석 후 AccountService에 위임.
     *
     * @param email           회원 이메일 (formLogin session principal)
     * @param currentPassword 현재 비밀번호 원문
     * @param newPassword     새 비밀번호 원문
     */
    void changePassword(String email, String currentPassword, String newPassword);

    /**
     * 회원 정보 수정 (name/phone).
     *
     * @param email 회원 이메일 (formLogin session principal)
     * @param name  변경할 이름
     * @param phone 변경할 전화번호 (null/빈 문자열 허용)
     */
    void updateProfile(String email, String name, String phone);

    /**
     * 탈퇴 처리 (소프트 삭제).
     *
     * @param email 회원 이메일 (formLogin session principal)
     */
    void withdraw(String email);
}
