package com.shop.shop.member.adapter;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.product.spi.ReviewerDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ReviewerDirectory} 포트의 member 모듈 구현체 (어댑터 — 의존 역전).
 *
 * <p>member 모듈이 소유하는 어댑터다. product 모듈 소유 포트 {@link ReviewerDirectory}를 구현한다.
 * {@link MemberRepository}를 통해 userId 집합으로 IN 배치 조회(N+1 금지)하고,
 * email 로컬파트 마스킹(앞 2자 + ***)을 member 측에서 수행한다.
 *
 * <p>의존 방향: member → product.spi(@NamedInterface) 단방향.
 * product는 member를 전혀 참조하지 않는다.
 *
 * <p>email 원문은 product로 절대 노출하지 않는다(개인정보 비노출).
 * 마스킹 책임이 회원 소유 모듈에 응집된다.
 */
@Component
@RequiredArgsConstructor
public class MemberReviewerDirectoryAdapter implements ReviewerDirectory {

    private final MemberRepository memberRepository;

    /**
     * userId 집합으로 마스킹된 표시명 배치 조회 (IN 쿼리 1회).
     *
     * <p>미존재 userId는 결과 맵에서 누락된다(폴백은 호출측 책임).
     *
     * @param userIds 조회할 userId 컬렉션
     * @return userId → 마스킹 표시명 맵
     */
    @Override
    public Map<Long, String> maskedDisplayNamesByUserId(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        List<Long> idList = userIds instanceof List<Long> l ? l : List.copyOf(userIds);
        return memberRepository.findAllById(idList).stream()
                .collect(Collectors.toMap(
                        user -> user.getId(),
                        user -> maskEmail(user.getEmail())
                ));
    }

    /**
     * email 로컬파트 마스킹 (앞 2자 + ***).
     *
     * <p>로컬파트가 1자인 경우: 1자 + *** 처리.
     * 로컬파트가 없는 비정상 email: email 그대로 반환(방어).
     *
     * <p>예: "alice@example.com" → "al***", "a@example.com" → "a***".
     *
     * @param email 원문 email
     * @return 마스킹된 표시명
     */
    static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        String localPart = email.substring(0, atIndex);
        int visibleChars = Math.min(2, localPart.length());
        return localPart.substring(0, visibleChars) + "***";
    }
}
