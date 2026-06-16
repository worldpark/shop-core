package com.shop.shop.member.service;

import com.shop.shop.common.exception.RoleChangeNotAllowedException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.spi.AdminMemberFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminMemberFacadeImpl} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(a) searchMembers — MemberService.searchMembers에 올바른 인자로 위임하는지</li>
 *   <li>(b) String → Role 변환 (role=null/빈값=전체, role="SELLER"=Role.SELLER 등)</li>
 *   <li>(c) email → userId 해석 (MemberService.getByEmail 호출)</li>
 *   <li>(d) User Entity → MemberSummaryResponse DTO 매핑 (role=String)</li>
 *   <li>(e) BusinessException이 변환 없이 그대로 전파되는지</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminMemberFacadeImplTest {

    @Mock
    private MemberService memberService;

    @Mock
    private MemberRepository memberRepository;

    private AdminMemberFacade facade;

    @BeforeEach
    void setUp() {
        facade = new AdminMemberFacadeImpl(memberService, memberRepository);
    }

    // ============================================================
    // searchMembers — 위임 및 String→Role 변환
    // ============================================================

    @Test
    @DisplayName("(a)(b) searchMembers — role=null이면 MemberService에 Role=null로 위임한다 (전체 조회)")
    void searchMembers_with_null_role_delegates_null_role_to_service() {
        User user = sampleUser(1L, "user@example.com", Role.SELLER);
        when(memberService.searchMembers(eq("키워드"), eq((Role) null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        Page<MemberSummaryResponse> result = facade.searchMembers("키워드", null, 0, 20);

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(memberService).searchMembers(eq("키워드"), roleCaptor.capture(), any(Pageable.class));
        assertThat(roleCaptor.getValue()).isNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("(b) searchMembers — role=빈문자열이면 MemberService에 Role=null로 위임한다 (전체 조회)")
    void searchMembers_with_blank_role_delegates_null_role_to_service() {
        when(memberService.searchMembers(any(), eq((Role) null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        facade.searchMembers(null, "", 0, 20);

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(memberService).searchMembers(any(), roleCaptor.capture(), any(Pageable.class));
        assertThat(roleCaptor.getValue()).isNull();
    }

    @Test
    @DisplayName("(b) searchMembers — role=\"SELLER\"이면 MemberService에 Role.SELLER로 변환해 위임한다")
    void searchMembers_with_seller_string_converts_to_role_enum() {
        when(memberService.searchMembers(any(), eq(Role.SELLER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        facade.searchMembers(null, "SELLER", 0, 20);

        verify(memberService).searchMembers(any(), eq(Role.SELLER), any(Pageable.class));
    }

    @Test
    @DisplayName("(b) searchMembers — role=\"ADMIN\"이면 MemberService에 Role.ADMIN으로 변환해 위임한다")
    void searchMembers_with_admin_string_converts_to_role_admin_enum() {
        when(memberService.searchMembers(any(), eq(Role.ADMIN), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        facade.searchMembers(null, "ADMIN", 0, 10);

        verify(memberService).searchMembers(any(), eq(Role.ADMIN), any(Pageable.class));
    }

    @Test
    @DisplayName("(a) searchMembers — page·size 인자가 PageRequest로 변환되어 위임된다")
    void searchMembers_converts_page_size_to_pageable() {
        when(memberService.searchMembers(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        facade.searchMembers(null, null, 2, 10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(memberService).searchMembers(any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("(d) searchMembers — User Entity가 MemberSummaryResponse DTO로 매핑된다 (role=String)")
    void searchMembers_maps_user_entity_to_dto_with_string_role() {
        User user = sampleUser(42L, "seller@example.com", Role.SELLER);
        when(memberService.searchMembers(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1L));

        Page<MemberSummaryResponse> result = facade.searchMembers(null, null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        MemberSummaryResponse dto = result.getContent().get(0);
        assertThat(dto.memberId()).isEqualTo(42L);
        assertThat(dto.email()).isEqualTo("seller@example.com");
        // role은 String으로 변환되어야 한다
        assertThat(dto.role()).isEqualTo("SELLER");
    }

    // ============================================================
    // changeRole — email→userId 해석, String→Role 변환
    // ============================================================

    @Test
    @DisplayName("(c) changeRole — adminEmail로 MemberService.getByEmail을 호출해 adminUserId를 획득한다")
    void changeRole_resolves_admin_user_id_from_email() {
        User adminUser = sampleUser(1L, "admin@example.com", Role.ADMIN);
        when(memberService.getByEmail("admin@example.com")).thenReturn(adminUser);

        facade.changeRole("admin@example.com", 3L, "SELLER");

        verify(memberService).getByEmail("admin@example.com");
    }

    @Test
    @DisplayName("(c)(b) changeRole — 획득한 adminUserId와 변환된 Role.SELLER로 MemberService.changeRole에 위임한다")
    void changeRole_delegates_with_resolved_user_id_and_converted_role() {
        User adminUser = sampleUser(1L, "admin@example.com", Role.ADMIN);
        when(memberService.getByEmail("admin@example.com")).thenReturn(adminUser);

        facade.changeRole("admin@example.com", 3L, "SELLER");

        verify(memberService).changeRole(1L, 3L, Role.SELLER);
    }

    @Test
    @DisplayName("(b) changeRole — role=\"CONSUMER\"이면 Role.CONSUMER로 변환해 위임한다")
    void changeRole_converts_consumer_string_to_role_enum() {
        User adminUser = sampleUser(1L, "admin@example.com", Role.ADMIN);
        when(memberService.getByEmail("admin@example.com")).thenReturn(adminUser);

        facade.changeRole("admin@example.com", 5L, "CONSUMER");

        verify(memberService).changeRole(1L, 5L, Role.CONSUMER);
    }

    @Test
    @DisplayName("(e) changeRole — MemberService가 BusinessException을 던지면 변환 없이 전파한다")
    void changeRole_propagates_business_exception_without_wrapping() {
        User adminUser = sampleUser(1L, "admin@example.com", Role.ADMIN);
        when(memberService.getByEmail("admin@example.com")).thenReturn(adminUser);
        doThrow(RoleChangeNotAllowedException.selfDemotion())
                .when(memberService).changeRole(1L, 1L, Role.CONSUMER);

        assertThatThrownBy(() -> facade.changeRole("admin@example.com", 1L, "CONSUMER"))
                .isInstanceOf(RoleChangeNotAllowedException.class);
    }

    // ============================================================
    // helpers
    // ============================================================

    private User sampleUser(long id, String email, Role role) {
        User user = User.of(email, "hash", "이름", null, role);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }
}
