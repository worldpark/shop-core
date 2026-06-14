package com.shop.shop.member.service;

import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DB 기반 UserDetailsService.
 * View formLogin 및 REST 로그인 자격증명 확인에 공용.
 * 식별자: email (citext — DB 레벨 대소문자 무시).
 */
@Service
@RequiredArgsConstructor
public class MemberUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    /**
     * 이메일로 UserDetails 조회.
     * authorities = ROLE_{role} (단일 권한).
     *
     * @param email 이메일 (Spring Security 표준 username 파라미터)
     * @throws UsernameNotFoundException 이메일 없음
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // 활성 회원만 조회 — 탈퇴(WITHDRAWN) 계정은 findActiveByEmail에서 empty 반환 → UsernameNotFoundException(C 정정).
        // findByEmail 대신 findActiveByEmail 사용으로 View formLogin 탈퇴 차단.
        User user = memberRepository.findActiveByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + email));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority(user.getRole().authority())))
                .build();
    }
}
