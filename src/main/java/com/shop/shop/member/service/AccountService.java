package com.shop.shop.member.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 계정 self-service 도메인 서비스.
 * 비밀번호 변경·정보 수정·탈퇴(소프트 삭제) 담당.
 * Repository는 이 클래스에서만 호출한다.
 *
 * <p>레이어: AccountServiceResponse(REST) / AccountFacadeImpl(View) → AccountService → MemberRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 비밀번호 변경.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>userId로 회원 조회 — 없으면 {@link MemberNotFoundException}</li>
     *   <li>현재 비밀번호 일치 검증 — 불일치 시 {@link BusinessException}(400) 거부</li>
     *   <li>새 비밀번호 BCrypt 인코딩 후 {@code user.changePassword(newHash)} (JPA dirty checking)</li>
     *   <li>refresh 무효화 이중 분기 (changeRole 선례 패턴 재사용)</li>
     * </ol>
     *
     * <p>로그: userId만 (비밀번호 원문/해시 로그 금지 — Constraint).
     *
     * @param userId          변경 대상 회원 ID (principal 본인)
     * @param currentPassword 현재 비밀번호 원문
     * @param newPassword     새 비밀번호 원문
     * @throws MemberNotFoundException 회원 없음 (이론상 — 로그인 직후)
     * @throws BusinessException       현재 비밀번호 불일치 (400)
     */
    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        User user = memberRepository.findById(userId)
                .orElseThrow(() -> new MemberNotFoundException(userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("현재 비밀번호가 일치하지 않습니다.");
        }

        String newHash = passwordEncoder.encode(newPassword);
        user.changePassword(newHash);

        log.info("비밀번호 변경 완료: userId={}", userId);

        invalidateRefresh(userId);
    }

    /**
     * 회원 정보 수정 (name/phone).
     *
     * <p>email/role/password는 이 경로로 변경 불가 (Constraint).
     * refresh 무효화 없음 — 인증 영향 없음 (name/phone은 JWT claims 미포함).
     *
     * @param userId 변경 대상 회원 ID (principal 본인)
     * @param name   변경할 이름
     * @param phone  변경할 전화번호 (null/빈 문자열 허용 — optional 필드)
     * @throws MemberNotFoundException 회원 없음 (이론상)
     */
    @Transactional
    public void updateProfile(long userId, String name, String phone) {
        User user = memberRepository.findById(userId)
                .orElseThrow(() -> new MemberNotFoundException(userId));

        user.updateProfile(name, normalizePhone(phone));

        log.info("회원 정보 수정 완료: userId={}", userId);
    }

    /**
     * 탈퇴 처리 (소프트 삭제).
     *
     * <p>물리 삭제 없음. {@code user.withdraw()}로 status=WITHDRAWN + deletedAt=now() 전이.
     * refresh 무효화 이중 분기로 재로그인 유도.
     *
     * @param userId 탈퇴 대상 회원 ID (principal 본인)
     * @throws MemberNotFoundException 회원 없음 (이론상)
     */
    @Transactional
    public void withdraw(long userId) {
        User user = memberRepository.findById(userId)
                .orElseThrow(() -> new MemberNotFoundException(userId));

        user.withdraw();

        log.info("회원 탈퇴 완료(소프트 삭제): userId={}", userId);

        invalidateRefresh(userId);
    }

    /**
     * 전화번호 정규화 — 빈 문자열을 null로 변환.
     * phone은 optional 필드이며 빈 문자열 대신 null을 저장한다 (MemberService 선례).
     */
    private String normalizePhone(String phone) {
        return StringUtils.hasText(phone) ? phone.trim() : null;
    }

    /**
     * refresh 무효화 이중 분기 헬퍼 (changeRole 선례 패턴 재사용).
     *
     * <p>Redis는 비트랜잭셔널(롤백 불가)이므로 트랜잭션 내 호출 시 DB 롤백 후 refresh만 삭제되는
     * 불일치가 발생할 수 있다. {@link TransactionSynchronization#afterCommit()}으로 커밋 후 호출한다.
     * 비트랜잭션 컨텍스트(일부 테스트)에서는 직접 호출한다.
     *
     * @param userId 무효화할 회원 ID
     */
    private void invalidateRefresh(long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshTokenStore.deleteRefresh(userId);
                    log.debug("refresh 무효화 완료(afterCommit): userId={}", userId);
                }
            });
        } else {
            // 트랜잭션 동기화 비활성 — 직접 호출 (Redis 롤백 불가 허용, 보안상 benign)
            refreshTokenStore.deleteRefresh(userId);
            log.debug("refresh 무효화 완료(직접): userId={}", userId);
        }
    }
}
