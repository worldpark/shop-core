package com.shop.shop.member.adapter;

import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemberReviewerDirectoryAdapter 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>여러 userId 입력 → email 로컬파트 마스킹(al***, bo***) Map 반환</li>
 *   <li>결과에 email 원문 미포함</li>
 *   <li>미존재 userId는 맵에서 누락(폴백은 호출측 책임)</li>
 *   <li>입력 컬렉션으로 member 배치 조회 1회(N+1 아님)</li>
 *   <li>1자 로컬파트 경계 처리</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberReviewerDirectoryAdapterTest {

    @Mock
    private MemberRepository memberRepository;

    private MemberReviewerDirectoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MemberReviewerDirectoryAdapter(memberRepository);
    }

    @Test
    @DisplayName("여러 userId 배치 조회 — 마스킹 Map 반환")
    void maskedDisplayNames_multipleUsers_maskedMap() {
        User alice = buildUser(1L, "alice@example.com");
        User bob = buildUser(2L, "bob@example.com");
        when(memberRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(alice, bob));

        Map<Long, String> result = adapter.maskedDisplayNamesByUserId(List.of(1L, 2L));

        assertThat(result).containsEntry(1L, "al***");
        assertThat(result).containsEntry(2L, "bo***");
    }

    @Test
    @DisplayName("결과에 email 원문 미포함")
    void maskedDisplayNames_noEmailInResult() {
        User alice = buildUser(1L, "alice@example.com");
        when(memberRepository.findAllById(List.of(1L))).thenReturn(List.of(alice));

        Map<Long, String> result = adapter.maskedDisplayNamesByUserId(List.of(1L));

        assertThat(result.get(1L)).doesNotContain("alice@example.com");
        assertThat(result.get(1L)).doesNotContain("@");
    }

    @Test
    @DisplayName("미존재 userId는 맵에서 누락")
    void maskedDisplayNames_nonExistentUser_notInMap() {
        when(memberRepository.findAllById(List.of(999L))).thenReturn(List.of());

        Map<Long, String> result = adapter.maskedDisplayNamesByUserId(List.of(999L));

        assertThat(result).doesNotContainKey(999L);
    }

    @Test
    @DisplayName("배치 조회 1회 — N+1 아님")
    void maskedDisplayNames_batchQueryOnce() {
        User u1 = buildUser(1L, "user1@example.com");
        User u2 = buildUser(2L, "user2@example.com");
        User u3 = buildUser(3L, "user3@example.com");
        when(memberRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(u1, u2, u3));

        adapter.maskedDisplayNamesByUserId(List.of(1L, 2L, 3L));

        verify(memberRepository, times(1)).findAllById(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("1자 로컬파트 경계 처리 — 'a@x.com' → 'a***'")
    void maskEmail_singleCharLocalPart() {
        assertThat(MemberReviewerDirectoryAdapter.maskEmail("a@x.com")).isEqualTo("a***");
    }

    @Test
    @DisplayName("2자 이상 로컬파트 — 앞 2자만 표시")
    void maskEmail_twoCharLocalPart() {
        assertThat(MemberReviewerDirectoryAdapter.maskEmail("ab@x.com")).isEqualTo("ab***");
        assertThat(MemberReviewerDirectoryAdapter.maskEmail("alice@x.com")).isEqualTo("al***");
    }

    @Test
    @DisplayName("빈 컬렉션 입력 → 빈 맵 반환")
    void maskedDisplayNames_emptyInput_emptyMap() {
        Map<Long, String> result = adapter.maskedDisplayNamesByUserId(List.of());
        assertThat(result).isEmpty();
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private User buildUser(long id, String email) {
        User user = User.of(email, "hash", "테스터", null, Role.CONSUMER);
        setField(user, "id", id);
        return user;
    }

    private void setField(Object obj, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = null;
            Class<?> cls = obj.getClass();
            while (cls != null) {
                try {
                    f = cls.getDeclaredField(fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            if (f == null) throw new NoSuchFieldException(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
