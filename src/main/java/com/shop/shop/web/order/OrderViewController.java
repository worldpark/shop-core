package com.shop.shop.web.order;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.dto.OrderCheckoutResponse;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.order.spi.OrderFacade;
import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentStatusView;
import com.shop.shop.payment.spi.PaymentFacade;
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
 * {@link PaymentFacade}(payment.spi published port) + {@link CurrentActorResolver}.
 * order/payment 도메인 내부 Service·Repository·Entity 직접 참조 금지.
 *
 * <p>모델 키 계약 (Backend-View Contract 준수):
 * <ul>
 *   <li>{@code checkout} — {@link OrderCheckoutResponse} (주문서 조회)</li>
 *   <li>{@code orders} — {@link Page}&lt;{@link OrderSummaryResponse}&gt; (주문 목록)</li>
 *   <li>{@code order} — {@link OrderResponse} (주문 상세)</li>
 *   <li>{@code payment} — {@link PaymentStatusView} (주문 상세 결제 영역)</li>
 * </ul>
 *
 * <p>PRG 패턴: POST /orders 성공 시 redirect:/orders/{orderId}.
 * POST /orders/{orderId}/payment 성공 시 flashSuccess + redirect:/orders/{orderId}.
 * 검증 실패·도메인 예외 시 flashError + redirect (각 경로 참조).
 *
 * <p>결제 폼 변환: {@link OrderPaymentForm} → {@link PaymentRequest} 변환은 핸들러 책임(#1).
 * web 타입을 facade에 직접 전달하지 않는다(architecture-rule, revision #1).
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
    private final PaymentFacade paymentFacade;
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
     * <p>결제 상태(모델 키 {@code payment})를 함께 조회해 결제 영역 렌더링에 사용한다.
     * getPaymentStatus는 상태 조회 전용 경로(이벤트 완결성 검증 없음, #3)를 사용하므로
     * 주문 상세 렌더링이 productId/연락처 해석 실패(409)에 영향받지 않는다.
     *
     * @param orderId            주문 ID (path variable)
     * @param auth               SecurityContext 인증 객체
     * @param model              Spring MVC 모델
     * @return view name "order/detail"
     */
    @GetMapping("/orders/{orderId}")
    public String orderDetail(
            @PathVariable long orderId,
            Authentication auth,
            Model model) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        OrderResponse order = orderFacade.getMyOrder(actor.email(), orderId);
        PaymentStatusView payment = paymentFacade.getPaymentStatus(actor.email(), orderId);
        model.addAttribute("order", order);
        model.addAttribute("payment", payment);
        return ORDER_DETAIL_VIEW;
    }

    /**
     * 결제 처리 (폼 제출).
     * POST /orders/{orderId}/payment
     *
     * <p>form→PaymentRequest 변환은 이 핸들러가 담당한다(web 계층 책임, #1 revision).
     * {@link OrderPaymentForm}을 facade에 직접 넘기지 않고,
     * {@link PaymentRequest}(payment 소유 DTO)로 변환 후 {@link PaymentFacade#pay}를 호출한다.
     *
     * <p>성공: flashSuccess("결제가 완료되었습니다.") + redirect:/orders/{orderId} (PRG).
     * 실패(BusinessException 400/409): flashError(메시지) + redirect:/orders/{orderId}.
     *
     * <p>인가: SecurityConfig View 체인 /orders/[orderId]/payment hasRole("CONSUMER").
     * 미인증 시 컨트롤러 도달 전 302 /login.
     *
     * @param orderId            주문 ID (path variable)
     * @param form               결제 폼 (method/amount — web 소유)
     * @param auth               SecurityContext 인증 객체
     * @param redirectAttributes flash 메시지 전달용
     * @return redirect:/orders/{orderId}
     */
    @PostMapping("/orders/{orderId}/payment")
    public String pay(
            @PathVariable long orderId,
            @ModelAttribute OrderPaymentForm form,
            Authentication auth,
            RedirectAttributes redirectAttributes) {

        String redirectTarget = "redirect:/orders/" + orderId;

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            // web 타입(OrderPaymentForm) → payment 소유 DTO(PaymentRequest) 변환 (revision #1)
            PaymentRequest paymentRequest = new PaymentRequest(form.getMethod(), form.getAmount());
            paymentFacade.pay(actor.email(), orderId, paymentRequest);
            redirectAttributes.addFlashAttribute("flashSuccess", "결제가 완료되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        }

        return redirectTarget;
    }
}
