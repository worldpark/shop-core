package com.shop.shop.member.domain;

/**
 * 판매자 신청 상태 열거형.
 *
 * <p>DB 저장값 = enum 상수명 — V5 CHECK(status IN ('PENDING','APPROVED','REJECTED')) 정합.
 * 상태 전이: PENDING → APPROVED | REJECTED (터미널 — 재전이 불가).
 */
public enum SellerApplicationStatus {

    /** 심사 대기 중 — 초기값, 부분 유니크 인덱스 대상 */
    PENDING,

    /** 승인됨 — 신청자가 SELLER로 승격된 상태 (터미널) */
    APPROVED,

    /** 반려됨 — 재신청 가능, 부분 유니크 인덱스 대상 아님 (터미널) */
    REJECTED
}
