package com.shop.shop.member.repository;

import com.shop.shop.member.domain.MemberStatus;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 회원 JPA 리포지토리.
 * 비즈니스 로직 없음 — MemberService에서만 호출.
 */
public interface MemberRepository extends JpaRepository<User, Long> {

    /**
     * 이메일로 회원 조회.
     * citext 컬럼이므로 DB 레벨에서 대소문자 무시 비교.
     * 탈퇴 회원을 포함한 전체 조회 — admin 검색 등 공유 경로에서 사용. 무변경.
     */
    Optional<User> findByEmail(String email);

    /**
     * 활성 회원을 이메일로 조회 (ACTIVE 상태만).
     * citext 컬럼이므로 DB 레벨에서 대소문자 무시 비교.
     * 탈퇴 차단 가드(MemberUserDetailsService, AccountFacade)에서 사용.
     * findByEmail은 무변경 — 시그니처 변경 시 admin 검색 등 회귀 차단.
     *
     * @param email 이메일
     * @return 활성 회원 (ACTIVE), 없거나 탈퇴면 Optional.empty()
     */
    @Query("select u from User u where u.email = :email and u.status = com.shop.shop.member.domain.MemberStatus.ACTIVE")
    Optional<User> findActiveByEmail(@Param("email") String email);

    /**
     * 관리자 회원 검색 — keyword(email/name 부분일치) + role 필터 + 페이지네이션.
     *
     * <p>keyword: null 또는 빈 문자열이면 전체 통과.
     *   - email은 citext 컬럼이므로 {@code like} 비교가 DB 레벨에서 대소문자를 무시한다.
     *   - name은 {@code lower()} 함수로 대소문자 무시 비교.
     * <p>role: null이면 전체 통과.
     *
     * @param keyword 검색 키워드 (이메일 또는 이름, null/빈 문자열 허용)
     * @param role    권한 필터 (null 허용)
     * @param pageable 페이지네이션 정보
     * @return 조건에 맞는 회원 페이지
     */
    @Query("""
            select u from User u
            where (:keyword is null or :keyword = ''
                   or u.email like concat('%', :keyword, '%')
                   or lower(u.name) like lower(concat('%', :keyword, '%')))
              and (:role is null or u.role = :role)
            """)
    Page<User> search(@Param("keyword") String keyword, @Param("role") Role role, Pageable pageable);

    /**
     * 특정 권한을 가진 회원 수 조회.
     * 마지막 ADMIN 강등 방지 불변식에 사용.
     *
     * @param role 권한
     * @return 해당 권한을 가진 회원 수
     */
    long countByRole(Role role);

    /**
     * 이메일 존재 여부 확인.
     * 회원가입 시 중복 이메일 사전 체크에 사용 (citext 대소문자 무시 비교).
     *
     * @param email 확인할 이메일
     * @return 존재하면 true
     */
    boolean existsByEmail(String email);
}
