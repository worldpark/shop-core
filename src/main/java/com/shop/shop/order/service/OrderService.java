package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import com.shop.shop.common.exception.EmptyCartException;
import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.OrderNumberGenerationException;
import com.shop.shop.common.exception.ProductNotPurchasableForOrderException;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.OrderItemOptionValue;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderOptionValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 도메인 서비스.
 *
 * <p>모든 메서드 첫 인자 long userId — principal 이중경로(REST=userId, View=email→userId) 통일.
 * Entity를 직접 반환하지 않음 — 내부 결과 타입 또는 ServiceResponse/FacadeImpl이 DTO 변환 담당.
 *
 * <p>주문 생성 트랜잭션 분리 구조 (plan 1.5):
 * <ul>
 *   <li>{@link #placeOrder}: non-transactional. orderNumber 충돌 시 트랜잭션 밖 bounded 재시도 (최대 3회).</li>
 *   <li>{@link #createOrderTx}: @Transactional. 1.3의 8단계 고정 흐름. DataIntegrityViolationException 전파.</li>
 * </ul>
 *
 * <p>두 메서드는 같은 빈 안에 있으므로 self-injection으로 프록시를 거쳐 호출한다.
 * 같은 빈 내부 호출은 프록시 미적용이므로, self-injection 없이 {@code this.createOrderTx(...)}로
 * 호출하면 @Transactional이 적용되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int MAX_RETRY = 3;
    private static final DateTimeFormatter ORDER_NUMBER_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String ORDER_NUMBER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ORDER_NUMBER_RANDOM_LENGTH = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CartCheckoutReader cartCheckoutReader;
    private final ProductOrderCatalog productOrderCatalog;
    private final InventoryStockPort inventoryStockPort;
    private final OrderRepository orderRepository;

    /**
     * Self-injection: 트랜잭션 프록시 적용을 위해 @Autowired @Lazy로 자기 자신 주입.
     * 같은 빈의 createOrderTx(@Transactional)를 직접 호출하면 프록시가 미적용되어
     * 새 트랜잭션이 열리지 않는다 — self 참조로 AOP 프록시를 통해 호출.
     *
     * <p>@Lazy: 순환 의존 감지 우회 (빈 생성 완료 후 프록시 참조를 lazy-resolve).
     * <p>package-private: 단위 테스트에서 self mock 교체 허용.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    OrderService self;

    /**
     * 주문 생성 — non-transactional, orderNumber 충돌 시 트랜잭션 밖 최대 3회 재시도.
     *
     * <p>매 시도마다 새 orderNumber를 생성하고 {@link #createOrderTx}를 새 트랜잭션으로 호출한다.
     * DataIntegrityViolationException (orderNumber unique 충돌): 트랜잭션 전체 롤백(재고 원복) 후 재시도.
     * 3회 초과 → {@link OrderNumberGenerationException}.
     *
     * @param userId  소유자 userId
     * @param request 배송지 정보
     * @return 생성된 주문 결과
     */
    public OrderResult placeOrder(long userId, OrderCreateRequest request) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            String orderNumber = generateOrderNumber();
            try {
                return self.createOrderTx(userId, request, orderNumber);
            } catch (DataIntegrityViolationException e) {
                log.warn("주문번호 충돌 재시도 {}회차: orderNumber={}", attempt, orderNumber);
                if (attempt == MAX_RETRY) {
                    throw new OrderNumberGenerationException(
                            "주문 번호 생성에 " + MAX_RETRY + "회 실패했습니다. 다시 시도해 주세요.");
                }
            }
        }
        throw new OrderNumberGenerationException();
    }

    /**
     * 주문 생성 트랜잭션 — @Transactional, 1.3 8단계 고정 흐름.
     *
     * <p>단계:
     * <ol>
     *   <li>장바구니 조회 → 빈 장바구니면 400(EmptyCartException)</li>
     *   <li>사전검증 스냅샷 조회(락 없음) → 구매불가/사전재고부족 → 409</li>
     *   <li>variantId 오름차순 비관적 락 획득 + 재고 차감</li>
     *   <li>권위 검증(isActive/stock) + 저장용 스냅샷 락 후 재조회</li>
     *   <li>재고 차감(3단계 decrease 호출이 내부 처리)</li>
     *   <li>order/items/optionValues 저장(락 후 스냅샷 값 사용)</li>
     *   <li>장바구니 비우기</li>
     *   <li>커밋</li>
     * </ol>
     *
     * <p>DataIntegrityViolationException(orderNumber unique 충돌)은 잡지 않고 전파.
     * 상위 placeOrder가 트랜잭션 밖 재시도 처리.
     *
     * @param userId      소유자 userId
     * @param request     배송지 정보
     * @param orderNumber 미리 생성된 주문 번호
     * @return 생성된 주문 결과
     */
    @Transactional
    public OrderResult createOrderTx(long userId, OrderCreateRequest request, String orderNumber) {
        // 1. 장바구니 조회
        CartCheckout checkout = cartCheckoutReader.getCheckoutCart(userId);
        if (checkout.items().isEmpty()) {
            throw new EmptyCartException();
        }

        List<CartCheckoutItem> cartItems = checkout.items();
        List<Long> variantIds = cartItems.stream()
                .map(CartCheckoutItem::variantId)
                .toList();

        // 2. 사전검증 스냅샷 조회(락 없음, advisory — 저장에 쓰지 않음)
        List<OrderableVariantSnapshot> advisorySnapshots = productOrderCatalog.getOrderableSnapshots(variantIds);
        Map<Long, OrderableVariantSnapshot> advisoryByVariantId = advisorySnapshots.stream()
                .collect(Collectors.toMap(OrderableVariantSnapshot::variantId, Function.identity()));

        for (CartCheckoutItem cartItem : cartItems) {
            OrderableVariantSnapshot snapshot = advisoryByVariantId.get(cartItem.variantId());
            if (snapshot == null || !snapshot.purchasable()) {
                throw new ProductNotPurchasableForOrderException();
            }
            if (cartItem.quantity() > snapshot.stock()) {
                throw new InsufficientStockException();
            }
        }

        // 3. variantId 오름차순 비관적 락 획득 + 재고 차감 (권위 검증 포함)
        List<CartCheckoutItem> sortedCartItems = cartItems.stream()
                .sorted(Comparator.comparingLong(CartCheckoutItem::variantId))
                .toList();

        for (CartCheckoutItem cartItem : sortedCartItems) {
            // InventoryStockPort.decrease 내부에서: 락 획득 → isActive/stock 검증 → 차감
            inventoryStockPort.decrease(cartItem.variantId(), cartItem.quantity());
        }

        // 4. 저장용 스냅샷 락 후 재조회 (2단계 advisory와 분리 — price=락 후 권위값)
        List<OrderableVariantSnapshot> authorizedSnapshots = productOrderCatalog.getOrderableSnapshots(
                variantIds.stream().sorted().collect(Collectors.toList())
        );
        Map<Long, OrderableVariantSnapshot> authorizedByVariantId = authorizedSnapshots.stream()
                .collect(Collectors.toMap(OrderableVariantSnapshot::variantId, Function.identity()));

        // 락 후 product.status 방어적 재검증
        for (CartCheckoutItem cartItem : cartItems) {
            OrderableVariantSnapshot snapshot = authorizedByVariantId.get(cartItem.variantId());
            if (snapshot == null || !snapshot.purchasable()) {
                throw new ProductNotPurchasableForOrderException("구매할 수 없는 상품 상태입니다.");
            }
        }

        // 5-6. order/items/optionValues 저장 (저장용 스냅샷 — 락 후 값 사용)
        BigDecimal itemsAmount = cartItems.stream()
                .map(cartItem -> {
                    OrderableVariantSnapshot snapshot = authorizedByVariantId.get(cartItem.variantId());
                    return snapshot.price().multiply(BigDecimal.valueOf(cartItem.quantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.create(
                userId, orderNumber, itemsAmount,
                request.recipient(), request.phone(), request.postcode(),
                request.address1(), request.address2()
        );

        for (CartCheckoutItem cartItem : cartItems) {
            OrderableVariantSnapshot snapshot = authorizedByVariantId.get(cartItem.variantId());
            OrderItem orderItem = OrderItem.create(
                    cartItem.variantId(),
                    snapshot.productName(),
                    snapshot.optionLabel(),
                    snapshot.price(), // 락 후 price
                    cartItem.quantity()
            );

            for (OrderOptionValue ov : snapshot.optionValues()) {
                orderItem.addOptionValue(
                        OrderItemOptionValue.create(ov.optionName(), ov.optionValue(), ov.sortOrder())
                );
            }

            order.addItem(orderItem);
        }

        Order savedOrder = orderRepository.save(order);

        // 7. 장바구니 비우기
        cartCheckoutReader.clearCart(userId);

        return new OrderResult(savedOrder.getId(), savedOrder.getOrderNumber());
    }

    /**
     * 내 주문 목록 조회 (최신순 페이지네이션).
     *
     * @param userId   소유자 userId
     * @param pageable 페이지 요청
     * @return 최신순 주문 목록 (OrderSummary 내부 타입)
     */
    @Transactional(readOnly = true)
    public Page<OrderSummary> getMyOrders(long userId, Pageable pageable) {
        Page<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        return orders.map(this::toOrderSummary);
    }

    /**
     * 내 주문 상세 조회.
     *
     * @param userId  소유자 userId
     * @param orderId 주문 ID
     * @return 주문 상세
     * @throws OrderNotFoundException 타인/미존재 주문 (404 존재 은닉)
     */
    @Transactional(readOnly = true)
    public OrderDetail getMyOrder(long userId, long orderId) {
        Order order = orderRepository.findWithItemsByIdAndUserId(orderId, userId)
                .orElseThrow(OrderNotFoundException::new);
        return toOrderDetail(order);
    }

    private OrderSummary toOrderSummary(Order order) {
        String representativeItemName = order.getItems().isEmpty()
                ? ""
                : order.getItems().get(0).getProductName();
        int itemCount = order.getItems().size();
        return new OrderSummary(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                representativeItemName,
                itemCount,
                order.getFinalAmount(),
                order.getCreatedAt()
        );
    }

    private OrderDetail toOrderDetail(Order order) {
        List<OrderItemDetail> itemDetails = order.getItems().stream()
                .map(item -> {
                    List<OrderOptionValueDetail> ovDetails = item.getOptionValues().stream()
                            .map(ov -> new OrderOptionValueDetail(ov.getOptionName(), ov.getOptionValue(), ov.getSortOrder()))
                            .toList();
                    return new OrderItemDetail(
                            item.getId(),
                            item.getVariantId(),
                            item.getProductName(),
                            item.getOptionLabel(),
                            ovDetails,
                            item.getUnitPrice(),
                            item.getQuantity(),
                            item.getLineAmount()
                    );
                })
                .toList();

        return new OrderDetail(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                itemDetails,
                order.getItemsAmount(),
                order.getDiscountAmount(),
                order.getShippingFee(),
                order.getFinalAmount(),
                order.getShipRecipient(),
                order.getShipPhone(),
                order.getShipPostcode(),
                order.getShipAddress1(),
                order.getShipAddress2(),
                order.getCreatedAt()
        );
    }

    /**
     * 고엔트로피 주문 번호 생성.
     *
     * <p>형식: ORD-yyyyMMdd-HHmmss-{대문자/숫자 8자 랜덤}
     * 충돌 확률을 실질적으로 0에 가깝게 한다.
     */
    private String generateOrderNumber() {
        String datetime = LocalDateTime.now().format(ORDER_NUMBER_FORMATTER);
        StringBuilder random = new StringBuilder(ORDER_NUMBER_RANDOM_LENGTH);
        for (int i = 0; i < ORDER_NUMBER_RANDOM_LENGTH; i++) {
            random.append(ORDER_NUMBER_CHARS.charAt(SECURE_RANDOM.nextInt(ORDER_NUMBER_CHARS.length())));
        }
        return "ORD-" + datetime + "-" + random;
    }

    // ============================================================
    // 내부 결과 타입 (Entity 미노출)
    // ============================================================

    /**
     * 주문 생성 결과 (orderId, orderNumber).
     */
    public record OrderResult(long orderId, String orderNumber) {}

    /**
     * 주문 목록 요약 (내부 타입).
     *
     * <p>representativeItemName = 첫 항목 productName.
     * itemCount = 주문 항목(라인) 수.
     */
    public record OrderSummary(
            long orderId,
            String orderNumber,
            String status,
            String representativeItemName,
            int itemCount,
            BigDecimal finalAmount,
            java.time.Instant createdAt
    ) {}

    /**
     * 주문 상세 (내부 타입, Entity 미노출).
     */
    public record OrderDetail(
            long orderId,
            String orderNumber,
            String status,
            List<OrderItemDetail> items,
            BigDecimal itemsAmount,
            BigDecimal discountAmount,
            BigDecimal shippingFee,
            BigDecimal finalAmount,
            String shipRecipient,
            String shipPhone,
            String shipPostcode,
            String shipAddress1,
            String shipAddress2,
            java.time.Instant createdAt
    ) {}

    /**
     * 주문 항목 상세 (내부 타입).
     */
    public record OrderItemDetail(
            Long itemId,
            Long variantId,
            String productName,
            String optionLabel,
            List<OrderOptionValueDetail> optionValues,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineAmount
    ) {}

    /**
     * 주문 항목 옵션값 상세 (내부 타입).
     */
    public record OrderOptionValueDetail(
            String optionName,
            String optionValue,
            int sortOrder
    ) {}

}
