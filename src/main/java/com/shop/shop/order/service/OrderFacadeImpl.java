package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.dto.OrderCheckoutResponse;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderItemOptionValueResponse;
import com.shop.shop.order.dto.OrderItemResponse;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.order.spi.OrderFacade;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link OrderFacade} 구현체 (package-private).
 *
 * <p>order 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link OrderFacade})만 참조하며, 이 구현체를 직접 알지 못한다 (CartFacadeImpl 선례).
 *
 * <p>책임:
 * <ul>
 *   <li>form-login email → userId 변환: {@link MemberDirectory#findUserIdByEmail(String)}</li>
 *   <li>OrderService 위임</li>
 *   <li>OrderDtoMapper를 통한 내부 타입 → DTO 변환</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class OrderFacadeImpl implements OrderFacade {

    private final OrderService orderService;
    private final MemberDirectory memberDirectory;
    private final CartCheckoutReader cartCheckoutReader;
    private final ProductOrderCatalog productOrderCatalog;
    private final OrderDtoMapper dtoMapper;

    /**
     * {@inheritDoc}
     *
     * <p>현재 장바구니 기반 주문서 합성:
     * CartCheckoutReader → ProductOrderCatalog → 주문 가능(purchasable) 항목 OrderItemResponse 조립
     * → itemsAmount=Σ lineAmount, discount=0, shipping=0, final=itemsAmount → OrderCheckoutResponse 반환.
     * 실제 주문 저장 없음 — View checkout 화면 렌더용. 재고 락·차감·clearCart 수행하지 않는다.
     */
    @Override
    public OrderCheckoutResponse getCheckout(String email) {
        long userId = memberDirectory.findUserIdByEmail(email);

        CartCheckout checkout = cartCheckoutReader.getCheckoutCart(userId);
        if (checkout.items().isEmpty()) {
            // 빈 장바구니 주문서 — 빈 OrderCheckoutResponse 반환 (예외 아님, View가 안내 표시)
            return new OrderCheckoutResponse(
                    List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        List<Long> variantIds = checkout.items().stream()
                .map(CartCheckoutItem::variantId)
                .toList();

        List<OrderableVariantSnapshot> snapshots = productOrderCatalog.getOrderableSnapshots(variantIds);
        Map<Long, OrderableVariantSnapshot> snapshotMap = snapshots.stream()
                .collect(Collectors.toMap(OrderableVariantSnapshot::variantId, Function.identity()));

        // purchasable 항목만 OrderItemResponse로 변환
        List<OrderItemResponse> items = checkout.items().stream()
                .filter(item -> {
                    OrderableVariantSnapshot snap = snapshotMap.get(item.variantId());
                    return snap != null && snap.purchasable();
                })
                .map(item -> {
                    OrderableVariantSnapshot snap = snapshotMap.get(item.variantId());
                    BigDecimal lineAmount = snap.price().multiply(BigDecimal.valueOf(item.quantity()));
                    List<OrderItemOptionValueResponse> optionValues = snap.optionValues().stream()
                            .map(ov -> new OrderItemOptionValueResponse(
                                    ov.optionName(), ov.optionValue(), ov.sortOrder()))
                            .toList();
                    return new OrderItemResponse(
                            null,
                            snap.variantId(),
                            snap.productName(),
                            snap.optionLabel(),
                            optionValues,
                            snap.price(),
                            item.quantity(),
                            lineAmount
                    );
                })
                .toList();

        BigDecimal itemsAmount = items.stream()
                .map(OrderItemResponse::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal shippingFee = BigDecimal.ZERO;
        BigDecimal finalAmount = itemsAmount.subtract(discountAmount).add(shippingFee);

        return new OrderCheckoutResponse(items, itemsAmount, discountAmount, shippingFee, finalAmount, !items.isEmpty());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderResponse createOrder(String email, OrderCreateRequest request) {
        long userId = memberDirectory.findUserIdByEmail(email);
        OrderService.OrderResult result = orderService.placeOrder(userId, request);
        OrderService.OrderDetail detail = orderService.getMyOrder(userId, result.orderId());
        return dtoMapper.toOrderResponse(detail);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<OrderSummaryResponse> getMyOrders(String email, Pageable pageable) {
        long userId = memberDirectory.findUserIdByEmail(email);
        Page<OrderService.OrderSummary> summaries = orderService.getMyOrders(userId, pageable);
        return summaries.map(dtoMapper::toOrderSummaryResponse);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderResponse getMyOrder(String email, long orderId) {
        long userId = memberDirectory.findUserIdByEmail(email);
        OrderService.OrderDetail detail = orderService.getMyOrder(userId, orderId);
        return dtoMapper.toOrderResponse(detail);
    }
}
