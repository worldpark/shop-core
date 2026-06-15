package com.shop.shop.product.spi;

import java.util.Collection;
import java.util.Map;

/**
 * 리뷰 작성자 표시명(마스킹) 배치 조회 포트 (product 소유, @NamedInterface("spi")).
 *
 * <p>product 모듈이 인터페이스를 소유(소비)하고 member/adapter/MemberReviewerDirectoryAdapter가 구현한다.
 *
 * <p>의존 방향: member → product.spi 단방향(UserDirectory/ProductOrderCatalog와 동일 패턴).
 * product는 member를 전혀 참조하지 않는다.
 *
 * <p>email 원문은 product로 절대 넘기지 않는다(개인정보 비노출).
 * 마스킹(email 로컬파트 앞 2자 + ***)은 member 측 adapter에서 수행한다.
 */
public interface ReviewerDirectory {

    /**
     * userId 집합으로 마스킹된 표시명 배치 조회 (IN 쿼리 1회 — N+1 금지).
     *
     * <p>결과 맵에 없는 userId는 호출측에서 기본 표시명("탈퇴회원" 등)으로 폴백한다.
     * email 원문은 포함되지 않으며 마스킹 완료 문자열만 반환한다.
     *
     * @param userIds 조회할 userId 컬렉션
     * @return userId → 마스킹 표시명 맵 (존재하는 userId만 포함)
     */
    Map<Long, String> maskedDisplayNamesByUserId(Collection<Long> userIds);
}
