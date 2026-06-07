package com.shop.shop.web.order;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.dto.OrderCheckoutResponse;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.order.spi.OrderFacade;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 주문 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /checkout}, {@code /orders}, {@code /orders/**}
 * → {@code hasRole("CONSUMER")}. 미인증 → 302 /login (컨트롤러 도달 전 Security 처리).
 *
 * <p>레이어: OrderViewController → {@link OrderFacade}(order.spi published port) +
 * {@link CurrentActorResolver}. order 도메인 내부 Service·Repository·Entity 직접 참조 금지.
 *
 * <p>모델 키 계약 (Backend-View Contract 준수):
 * <ul>
 *   <li>{@code checkout} — {@link OrderCheckoutResponse} (주문서 조회)</li>
 *   <li>{@code orders} — {@link Page}&lt;{@link OrderSummaryResponse}&gt; (주문 목록)</li>
 *   <li>{@code order} — {@link OrderResponse} (주문 상세)</li>
 * </ul>
 *
 * <p>PRG 패턴: POST /orders 성공 시 redirect:/orders/{orderId}.
 * 검증 실패·도메인 예외 시 flashError + redirect:/checkout.
 *
 * <p>예외 전파: facade가 던지는 {@link com.shop.shop.common.exception.OrderNotFoundException}(404)는
 * ViewExceptionHandler → error/error 뷰. 컨트롤러에서 별도 분기 불필요.
 */
@Controller
@RequiredArgsConstructor
public class OrderViewController {

    private static final String CHECKOUT_VIEW = "order/checkout";
    private static final String ORDER_LIST_VIEW = "order/list";
    private static final String ORDER_DETAIL_VIEW = "order/detail";
    private static final String REDIRECT_CHECKOUT = "redirect:/checkout";

    private final OrderFacade orderFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 주문서 화면 (체크아웃).
     * GET /checkout
     *
     * <p>현재 장바구니 기반 합성 주문서 표시. 주문 생성 폼 렌더링.
     *
     * @param auth  SecurityContext 인증 객체 (username=email, form-login)
     * @param model Spring MVC 모델
     * @return view name "order/checkout"
     */
    @GetMapping("/checkout")
    public String checkout(Authentication auth, Model model) {
        CurrentActor actor = currentActorResolver.resolve(auth);
        OrderCheckoutResponse checkout = orderFacade.getCheckout(actor.email());
        model.addAttribute("checkout", checkout);
        return CHECKOUT_VIEW;
    }

    /**
     * 주문 생성 (폼 제출).
     * POST /orders
     *
     * <p>검증 실패: flashError + redirect:/checkout.
     * 성공: OrderFacade.createOrder → redirect:/orders/{orderId} (PRG).
     * 도메인 예외(409/400): flashError 후 redirect:/checkout.
     *
     * @param form               배송지 폼 (recipient/phone/postcode/address1/address2)
     * @param bindingResult      폼 검증 결과
     * @param auth               SecurityContext 인증 객체
     * @param redirectAttributes flashError 전달용
     * @return redirect
     */
    @PostMapping("/orders")
    public String createOrder(
            @Valid @ModelAttribute OrderCreateForm form,
            BindingResult bindingResult,
            Authentication auth,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            String errorMessage = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("flashError", errorMessage);
            return REDIRECT_CHECKOUT;
        }

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            OrderCreateRequest request = new OrderCreateRequest(
                    form.getRecipient(),
                    form.getPhone(),
                    form.getPostcode(),
                    form.getAddress1(),
                    form.getAddress2()
            );
            OrderResponse created = orderFacade.createOrder(actor.email(), request);
            return "redirect:/orders/" + created.orderId();
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return REDIRECT_CHECKOUT;
        }
    }

    /**
     * 내 주문 목록 화면.
     * GET /orders
     *
     * <p>최신순 페이지네이션.
     *
     * @param auth     SecurityContext 인증 객체
     * @param pageable 페이지 요청 (기본 page=0, size=10, 최신순)
     * @param model    Spring MVC 모델
     * @return view name "order/list"
     */
    @GetMapping("/orders")
    public String listOrders(
            Authentication auth,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        Page<OrderSummaryResponse> orders = orderFacade.getMyOrders(actor.email(), pageable);
        model.addAttribute("orders", orders);
        return ORDER_LIST_VIEW;
    }

    /**
     * 내 주문 상세 화면.
     * GET /orders/{orderId}
     *
     * <p>타인/미존재 → OrderFacade가 OrderNotFoundException(404) → ViewExceptionHandler error/error.
     * 컨트롤러에서 별도 분기 불필요.
     *
     * @param orderId 주문 ID (path variable)
     * @param auth    SecurityContext 인증 객체
     * @param model   Spring MVC 모델
     * @return view name "order/detail"
     */
    @GetMapping("/orders/{orderId}")
    public String orderDetail(
            @PathVariable long orderId,
            Authentication auth,
            Model model) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        OrderResponse order = orderFacade.getMyOrder(actor.email(), orderId);
        model.addAttribute("order", order);
        return ORDER_DETAIL_VIEW;
    }
}
