package com.shop.shop.member.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.mockito.Mockito.verify;

/**
 * LoginActivityRecorder 단위 테스트.
 *
 * <p>InteractiveAuthenticationSuccessEvent 수신 시 MemberService.recordLoginByEmail이
 * 이메일(authentication.getName())로 호출되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LoginActivityRecorderTest {

    @Mock
    private MemberService memberService;

    @InjectMocks
    private LoginActivityRecorder loginActivityRecorder;

    @Test
    @DisplayName("InteractiveAuthenticationSuccessEvent 수신 시 recordLoginByEmail(email) 호출")
    void onInteractiveAuthenticationSuccess_calls_recordLoginByEmail() {
        // given
        String email = "form-user@example.com";
        Authentication auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        InteractiveAuthenticationSuccessEvent event =
                new InteractiveAuthenticationSuccessEvent(auth, LoginActivityRecorder.class);

        // when
        loginActivityRecorder.onInteractiveAuthenticationSuccess(event);

        // then
        verify(memberService).recordLoginByEmail(email);
    }
}
