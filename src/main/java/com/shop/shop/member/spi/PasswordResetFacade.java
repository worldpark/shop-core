package com.shop.shop.member.spi;

/**
 * 비밀번호 재설정 View 전용 facade (published port).
 *
 * <p>web 모듈의 PasswordResetViewController가 member 도메인 내부 Service·Entity를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>facade 시그니처는 web 타입(폼 객체)을 파라미터·반환 타입으로 받지 않는다.
 * scalar(String/boolean)만 노출하고, web 폼 → scalar 변환은 ViewController 책임이다
 * (architecture-rule: published API가 web을 역참조하지 않게 한다).
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 */
public interface PasswordResetFacade {

    /**
     * 비밀번호 재설정 요청.
     * 이메일 존재 여부와 무관하게 정상 반환 (enumeration 방지).
     *
     * @param email 재설정 요청 이메일
     */
    void requestReset(String email);

    /**
     * 토큰 유효성 확인 (비소비 peek).
     * GET confirm 화면에서 폼 표시 여부 결정에 사용.
     * consume을 호출하지 않아 새로고침으로 토큰이 소진되는 사고를 막는다.
     *
     * @param token 재설정 토큰 원문
     * @return 유효하면 true, 만료/미존재이면 false
     */
    boolean isTokenValid(String token);

    /**
     * 비밀번호 재설정 확정.
     * 토큰 원자 소비 후 비밀번호 교체.
     *
     * @param token       재설정 토큰 원문
     * @param newPassword 새 비밀번호 원문
     */
    void confirmReset(String token, String newPassword);
}
