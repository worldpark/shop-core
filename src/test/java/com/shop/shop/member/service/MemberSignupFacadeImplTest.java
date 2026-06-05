package com.shop.shop.member.service;

import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.member.spi.MemberSignupFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

/**
 * {@link MemberSignupFacadeImpl} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(a) MemberService.signup에 올바른 인자로 위임하는지</li>
 *   <li>(b) DuplicateEmailException이 변환 없이 그대로 전파되는지</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberSignupFacadeImplTest {

    @Mock
    private MemberService memberService;

    private MemberSignupFacade facade;

    @BeforeEach
    void setUp() {
        facade = new MemberSignupFacadeImpl(memberService);
    }

    @Test
    @DisplayName("(a) signup — MemberService.signup에 모든 인자를 그대로 위임한다")
    void signup_delegates_all_arguments_to_member_service() {
        String email = "user@example.com";
        String password = "password123";
        String name = "홍길동";
        String phone = "010-1234-5678";

        facade.signup(email, password, name, phone);

        verify(memberService).signup(email, password, name, phone);
    }

    @Test
    @DisplayName("(a) signup — phone이 null인 경우에도 MemberService.signup에 그대로 위임한다")
    void signup_delegates_with_null_phone() {
        facade.signup("user@example.com", "pass", "홍길동", null);

        verify(memberService).signup("user@example.com", "pass", "홍길동", null);
    }

    @Test
    @DisplayName("(b) signup — MemberService가 DuplicateEmailException을 던지면 변환 없이 전파한다")
    void signup_propagates_duplicate_email_exception_without_wrapping() {
        doThrow(new DuplicateEmailException())
                .when(memberService).signup("dup@example.com", "pass", "이름", null);

        assertThatThrownBy(() -> facade.signup("dup@example.com", "pass", "이름", null))
                .isInstanceOf(DuplicateEmailException.class);
    }
}
