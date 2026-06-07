package com.shop.shop.web.cart;

import com.shop.shop.cart.dto.CartResponse;
import com.shop.shop.cart.spi.CartFacade;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 장바구니 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /cart}, {@code /cart/**} → {@code hasRole("CONSUMER")}.
 * 미인증 → 302 /login (컨트롤러 도달 전 Security 처리).
 *
 * <p>레이어: CartViewController → {@link CartFacade}(cart.spi published port) + {@link CurrentActorResolver}.
 * cart 도메인 내부 Service·Repository·Entity 직접 참조 금지.
 *
 * <p>모델 키 계약 (Backend-View Contract 준수):
 * <ul>
 *   <li>{@code cart} — {@link CartResponse} (장바구니 조회 결과)</li>
 * </ul>
 * View name: {@code cart/index}
 *
 * <p>PRG 패턴: POST 요청 성공 시 redirect:/cart. 실패 시 flashError + redirect.
 *
 * <p>예외 전파: facade가 던지는 BusinessException(404 등)은 ViewExceptionHandler → error/error 뷰.
 * quantity/variantId 검증 실패는 flashError 후 redirect.
 */
@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartViewController {

    private static final String CART_INDEX_VIEW = "cart/index";
    private static final String REDIRECT_CART = "redirect:/cart";

    private final CartFacade cartFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 장바구니 화면.
     * GET /cart
     *
     * <p>CartFacade.getCart(email) → 모델 cart(CartResponse) → view cart/index.
     *
     * @param auth  SecurityContext 인증 객체 (username=email, form-login)
     * @param model Spring MVC 모델
     * @return view name "cart/index"
     */
    @GetMapping
    public String viewCart(Authentication auth, Model model) {
        CurrentActor actor = currentActorResolver.resolve(auth);
        CartResponse cart = cartFacade.getCart(actor.email());
        model.addAttribute("cart", cart);
        return CART_INDEX_VIEW;
    }

    /**
     * 장바구니 담기 (상세화면 폼 제출).
     * POST /cart/items
     *
     * <p>검증 실패: flashError + redirect:/cart (또는 원래 화면).
     * 성공: CartFacade.addItem(email, variantId, quantity) → redirect:/cart (PRG).
     * 도메인 예외(400/404): ViewExceptionHandler → error/error 뷰.
     *
     * @param form              담기 폼 (variantId, quantity)
     * @param bindingResult     폼 검증 결과
     * @param auth              SecurityContext 인증 객체
     * @param redirectAttributes flashError 전달용
     * @return redirect
     */
    @PostMapping("/items")
    public String addItem(
            @Valid @ModelAttribute CartItemAddForm form,
            BindingResult bindingResult,
            Authentication auth,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            String errorMessage = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("flashError", errorMessage);
            return REDIRECT_CART;
        }

        CurrentActor actor = currentActorResolver.resolve(auth);
        cartFacade.addItem(actor.email(), form.getVariantId(), form.getQuantity());
        return REDIRECT_CART;
    }

    /**
     * 장바구니 항목 수량 변경.
     * POST /cart/items/{cartItemId}
     *
     * <p>절대값 수량 변경 (last-write-wins). 검증 실패 또는 404 → flashError + redirect:/cart.
     * 도메인 예외: ViewExceptionHandler → error/error 뷰.
     *
     * @param cartItemId        수량 변경할 항목 ID
     * @param form              수량 변경 폼 (quantity 절대값)
     * @param bindingResult     폼 검증 결과
     * @param auth              SecurityContext 인증 객체
     * @param redirectAttributes flashError 전달용
     * @return redirect:/cart
     */
    @PostMapping("/items/{cartItemId}")
    public String updateQuantity(
            @PathVariable long cartItemId,
            @Valid @ModelAttribute CartItemQuantityForm form,
            BindingResult bindingResult,
            Authentication auth,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            String errorMessage = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("flashError", errorMessage);
            return REDIRECT_CART;
        }

        CurrentActor actor = currentActorResolver.resolve(auth);
        cartFacade.updateQuantity(actor.email(), cartItemId, form.getQuantity());
        return REDIRECT_CART;
    }

    /**
     * 장바구니 항목 삭제.
     * POST /cart/items/{cartItemId}/delete
     *
     * <p>타인/미존재 → facade가 CartItemNotFoundException(404) → ViewExceptionHandler error/error.
     * 성공 → redirect:/cart.
     *
     * @param cartItemId        삭제할 항목 ID
     * @param auth              SecurityContext 인증 객체
     * @return redirect:/cart
     */
    @PostMapping("/items/{cartItemId}/delete")
    public String removeItem(
            @PathVariable long cartItemId,
            Authentication auth) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        cartFacade.removeItem(actor.email(), cartItemId);
        return REDIRECT_CART;
    }
}
