package com.shop.shop.member.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 관리자 회원 검색 조건 폼 백킹 객체.
 *
 * <p>View {@code @ModelAttribute("searchCondition")} 바인딩(검색폼 echo)에 사용한다.
 *
 * <p>가변 class — Thymeleaf {@code @ModelAttribute} 바인딩 및 스프링 MVC
 * 쿼리 파라미터 바인딩이 기본 생성자 + setter를 필요로 하므로 record 대신 class 사용.
 *
 * <p>role은 {@code String} 타입 — web 모듈이 도메인 enum({@code Role})을 직접 참조하지 않도록
 * View 전용 facade 경계에서 String으로 받는다. null/빈 문자열 = 전체 조회.
 */
@Getter
@Setter
@NoArgsConstructor
public class MemberSearchCondition {

    /** 이메일 또는 이름 검색 키워드 (null/빈 문자열 = 전체) */
    private String keyword;

    /** 권한 필터 문자열 (null/빈 문자열 = 전체, 그 외 = ADMIN/SELLER/CONSUMER) */
    private String role;

    /** 페이지 번호 (0 기반, 기본값 0) */
    private int page = 0;

    /** 페이지 크기 (기본값 20) */
    private int size = 20;
}
