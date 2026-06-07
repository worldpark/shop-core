package com.shop.shop.member.service;

import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.spi.MemberDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * MemberDirectoryImpl 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>email → userId 조회 성공 (scalar만 반환)</li>
 *   <li>member Entity 미노출 (userId long 반환)</li>
 *   <li>미존재 email → IllegalStateException (시스템 불변식)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberDirectoryImplTest {

    @Mock
    private MemberService memberService;

    private MemberDirectory memberDirectory;

    private static final String EMAIL = "user@example.com";
    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        memberDirectory = new MemberDirectoryImpl(memberService);
    }

    @Test
    @DisplayName("email → userId 조회 성공 — scalar userId(long)만 반환")
    void findUserIdByEmail_success_returnsUserId() {
        User user = User.of(EMAIL, "hash", "홍길동", null, Role.CONSUMER);
        setUserId(user, USER_ID);
        when(memberService.getByEmail(EMAIL)).thenReturn(user);

        long result = memberDirectory.findUserIdByEmail(EMAIL);

        assertThat(result).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("반환값은 long scalar — member Entity 미노출")
    void findUserIdByEmail_returnTypeLong_notEntity() {
        // 인터페이스 시그니처 자체가 long 반환이므로 컴파일타임 검증
        // 런타임에도 Entity 참조 없음을 단언
        User user = User.of(EMAIL, "hash", "홍길동", null, Role.CONSUMER);
        setUserId(user, USER_ID);
        when(memberService.getByEmail(EMAIL)).thenReturn(user);

        Object result = memberDirectory.findUserIdByEmail(EMAIL);

        assertThat(result).isInstanceOf(Long.class);
        assertThat(result).isNotInstanceOf(User.class);
    }

    @Test
    @DisplayName("미존재 email → IllegalStateException (인증 세션-디렉터리 불일치)")
    void findUserIdByEmail_notExisting_throwsIllegalStateException() {
        when(memberService.getByEmail(EMAIL))
                .thenThrow(new MemberNotFoundException(EMAIL));

        assertThatThrownBy(() -> memberDirectory.findUserIdByEmail(EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EMAIL);
    }

    private void setUserId(User user, long userId) {
        try {
            Field field = findField(user.getClass(), "id");
            field.setAccessible(true);
            field.set(user, userId);
        } catch (Exception e) {
            throw new RuntimeException("id 필드 설정 실패", e);
        }
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }
}
