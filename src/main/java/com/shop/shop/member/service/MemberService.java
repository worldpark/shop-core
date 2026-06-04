package com.shop.shop.member.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.common.exception.InvalidCredentialsException;
import com.shop.shop.common.exception.InvalidTokenException;
import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.common.exception.RoleChangeNotAllowedException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 회원 도메인 서비스.
 * 로그인 자격증명 검증, 사용자 조회, 검색, 권한 변경 담당.
 * Repository는 이 클래스에서만 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 이메일/비밀번호 자격증명 검증.
     *
     * <p>계정 열거 방지: 이메일 없음 / 비밀번호 불일치 모두 동일 메시지 반환.
     * 비밀번호 원문 로그 금지 (Constraint).
     *
     * @param email       로그인 이메일
     * @param rawPassword 비밀번호 원문
     * @return 인증된 User
     * @throws InvalidCredentialsException 이메일 없음 또는 비밀번호 불일치
     */
    @Transactional(readOnly = true)
    public User authenticate(String email, String rawPassword) {
        User user = memberRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return user;
    }

    /**
     * 회원 ID로 사용자 조회.
     * /me 응답 및 refresh 시 사용자 정보 확인용.
     *
     * @param userId 회원 식별자
     * @return 조회된 User
     * @throws InvalidTokenException userId에 해당하는 사용자 없음
     */
    @Transactional(readOnly = true)
    public User getById(long userId) {
        return memberRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
    }

    /**
     * 이메일로 회원 조회.
     * View 진입점에서 form login session principal(email) → userId 통일에 사용.
     *
     * @param email 이메일
     * @return 조회된 User
     * @throws MemberNotFoundException 이메일에 해당하는 사용자 없음
     */
    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new MemberNotFoundException(email));
    }

    /**
     * 일반 사용자 회원가입.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>이메일 정규화(trim) — citext가 대소문자를 DB 레벨에서 처리</li>
     *   <li>이메일 중복 사전 체크 → {@link DuplicateEmailException}(409)</li>
     *   <li>BCrypt 해시 생성 — 원문 DB/로그 미저장</li>
     *   <li>User 저장 — 기본 role CONSUMER 강제(요청에서 role 미수신)</li>
     *   <li>동시성 경합 unique 위반 흡수 — {@link DataIntegrityViolationException} → {@link DuplicateEmailException}</li>
     * </ol>
     *
     * @param email       가입 이메일
     * @param rawPassword 비밀번호 원문
     * @param name        이름
     * @param phone       전화번호 (optional, null/빈 문자열 허용)
     * @return 저장된 User
     * @throws DuplicateEmailException 이메일 중복 또는 동시성 경합 unique 위반
     */
    @Transactional
    public User signup(String email, String rawPassword, String name, String phone) {
        String normalizedEmail = email.trim();   // 정규화: 앞뒤 공백 제거. citext가 대소문자 처리.

        // 이메일 중복 사전 체크
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException();
        }

        String hash = passwordEncoder.encode(rawPassword);   // BCrypt 해시 — 원문 미저장

        try {
            User user = memberRepository.save(
                    User.of(normalizedEmail, hash, name.trim(), normalizePhone(phone), Role.CONSUMER));
            log.info("회원가입 완료: userId={}", user.getId());   // 원문/해시 로그 금지 (Constraint)
            return user;
        } catch (DataIntegrityViolationException e) {
            // 동시성 경합: 사전 체크 통과 후 INSERT 시 unique 위반 — DuplicateEmailException으로 변환
            throw new DuplicateEmailException();
        }
    }

    /**
     * 전화번호 정규화 — 빈 문자열을 null로 변환.
     * phone은 optional 필드이며 빈 문자열 대신 null을 저장한다.
     */
    private String normalizePhone(String phone) {
        return StringUtils.hasText(phone) ? phone.trim() : null;
    }

    /**
     * 관리자 회원 검색.
     * keyword(email/name 부분일치) + role 필터 + 페이지네이션.
     *
     * @param keyword  검색 키워드 (이메일 또는 이름, null/빈 문자열 = 전체)
     * @param role     권한 필터 (null = 전체)
     * @param pageable 페이지네이션 정보
     * @return 조건에 맞는 회원 페이지
     */
    @Transactional(readOnly = true)
    public Page<User> searchMembers(String keyword, Role role, Pageable pageable) {
        return memberRepository.search(keyword, role, pageable);
    }

    /**
     * 관리자 회원 권한 변경.
     *
     * <p>불변식 순서:
     * <ol>
     *   <li>대상 존재 확인 — 없으면 {@link MemberNotFoundException}(404)</li>
     *   <li>ADMIN 승격 금지 — newRole == ADMIN이면 {@link RoleChangeNotAllowedException#forbiddenPromotion()}(400)</li>
     *   <li>자기 자신 ADMIN 강등 금지 — adminUserId == targetId && target.role == ADMIN이면 {@link RoleChangeNotAllowedException#selfDemotion()}(409)</li>
     *   <li>마지막 ADMIN 강등 금지 — target.role == ADMIN && countByRole(ADMIN) &lt;= 1이면 {@link RoleChangeNotAllowedException#lastAdmin()}(409)</li>
     *   <li>변경 적용 — {@code target.changeRole(newRole)}(JPA dirty checking으로 커밋 시 UPDATE)</li>
     * </ol>
     *
     * <p>refresh 무효화: DB 변경 커밋 후 {@code refreshTokenStore.deleteRefresh(targetId)} 호출.
     * Redis는 비트랜잭셔널(롤백 불가)이므로 트랜잭션 내 호출 시 DB 롤백 후 refresh만 삭제되는
     * 불일치가 발생할 수 있다. {@link TransactionSynchronization#afterCommit()}으로 커밋 후 호출한다.
     * deleteRefresh 실패 시에도 "재로그인 강제"는 보안상 benign.
     *
     * <p>access token은 만료(≤ access TTL 30분)까지 기존 권한으로 동작한다.
     * access jti 전수 추적/per-user token-version이 없어 즉시 무효화는 과설계 — 보류.
     *
     * @param adminUserId 행위 관리자 userId (자기 강등 차단용)
     * @param targetId    변경 대상 회원 userId
     * @param newRole     변경할 권한 (SELLER 또는 CONSUMER)
     * @throws MemberNotFoundException          대상 회원 없음 (404)
     * @throws RoleChangeNotAllowedException    불변식 위반 (400/409)
     */
    @Transactional
    public void changeRole(long adminUserId, long targetId, Role newRole) {
        // 1. 대상 존재 확인
        User target = memberRepository.findById(targetId)
                .orElseThrow(() -> new MemberNotFoundException(targetId));

        Role oldRole = target.getRole();

        // 2. ADMIN 승격 금지
        if (newRole == Role.ADMIN) {
            log.warn("role 변경 거부(ADMIN 승격): adminUserId={}, targetId={}", adminUserId, targetId);
            throw RoleChangeNotAllowedException.forbiddenPromotion();
        }

        // 3, 4. target이 ADMIN인 경우에만 자기 강등 / 마지막 ADMIN 검사
        if (oldRole == Role.ADMIN) {
            // 3. 자기 자신 ADMIN 강등 금지
            if (adminUserId == targetId) {
                log.warn("role 변경 거부(자기 ADMIN 강등): adminUserId={}, targetId={}", adminUserId, targetId);
                throw RoleChangeNotAllowedException.selfDemotion();
            }

            // 4. 마지막 ADMIN 강등 금지
            if (memberRepository.countByRole(Role.ADMIN) <= 1) {
                log.warn("role 변경 거부(마지막 ADMIN): adminUserId={}, targetId={}", adminUserId, targetId);
                throw RoleChangeNotAllowedException.lastAdmin();
            }
        }

        // 5. 변경 적용 (JPA dirty checking — 커밋 시 UPDATE)
        target.changeRole(newRole);
        log.info("role 변경: adminUserId={}, targetId={}, {} -> {}", adminUserId, targetId, oldRole, newRole);

        // refresh 무효화: DB 커밋 후 호출 (Redis 롤백 불가)
        // 트랜잭션 동기화가 활성화된 경우(운영): afterCommit 등록으로 커밋 이후 실행 보장.
        // 트랜잭션 동기화가 비활성화된 경우(일부 테스트/비트랜잭션 컨텍스트): 메서드 말미에서 직접 호출.
        // NOTE: access token은 만료(≤ access TTL 30분)까지 기존 권한으로 동작한다.
        //       access jti 전수 추적/per-user token-version이 없어 즉시 무효화는 과설계 — 보류.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshTokenStore.deleteRefresh(targetId);
                    log.debug("refresh 무효화 완료(afterCommit): targetId={}", targetId);
                }
            });
        } else {
            // 트랜잭션 동기화 비활성 — 직접 호출 (Redis 롤백 불가 허용, 보안상 benign)
            refreshTokenStore.deleteRefresh(targetId);
            log.debug("refresh 무효화 완료(직접): targetId={}", targetId);
        }
    }
}
