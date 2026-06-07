package com.shop.shop.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 장바구니 종단(E2E) 스모크.
 *
 * <p>회귀: 장바구니를 한 번도 만든 적 없는 신규 회원이 {@code /cart}에 처음 들어가면
 * 과거 {@code CartService.getCart}가 미영속 Cart(id=null)를 {@code findByCartId(long)}에 넘겨
 * NPE → 에러 페이지가 떴다. 빈 장바구니가 정상 렌더되는지 실제 브라우저로 확인한다.
 *
 * <p>수명주기·인증 헬퍼는 {@link AbstractE2eTest} 참고. {@code /cart}는 ROLE_CONSUMER 필요 —
 * 회원가입은 CONSUMER를 부여하므로 신규 회원으로 접근 가능하다.
 */
class CartE2eTest extends AbstractE2eTest {

    @Test
    @DisplayName("장바구니를 만든 적 없는 신규 회원도 빈 장바구니 페이지가 정상 렌더된다 (NPE 회귀)")
    void emptyCartPageRendersForBrandNewUser() {
        signupAndLogin();

        page.navigate("/cart");

        // 에러 페이지가 아니라 빈 장바구니 화면이 떠야 한다
        assertThat(page).hasURL(BASE_URL + "/cart");
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("장바구니"))).isVisible();
        assertThat(page.getByText("장바구니가 비어 있습니다.")).isVisible();
    }
}
