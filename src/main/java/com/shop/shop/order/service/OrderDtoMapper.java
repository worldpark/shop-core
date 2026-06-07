package com.shop.shop.order.service;

import com.shop.shop.order.dto.OrderItemOptionValueResponse;
import com.shop.shop.order.dto.OrderItemResponse;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.order.dto.ShippingAddressResponse;
import com.shop.shop.order.service.OrderService.OrderDetail;
import com.shop.shop.order.service.OrderService.OrderItemDetail;
import com.shop.shop.order.service.OrderService.OrderOptionValueDetail;
import com.shop.shop.order.service.OrderService.OrderSummary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 주문 내부 결과 타입 → 응답 DTO 변환 매퍼 (package-private).
 *
 * <p>OrderServiceResponse·OrderFacadeImpl이 공유한다 (CartDtoMapper 선례).
 * ownerId/Entity 미노출 변환을 한 곳에 집중.
 */
@Component
class OrderDtoMapper {

    /**
     * OrderDetail(내부 타입) → OrderResponse(응답 DTO).
     */
    OrderResponse toOrderResponse(OrderDetail detail) {
        List<OrderItemResponse> itemResponses = detail.items().stream()
                .map(this::toOrderItemResponse)
                .toList();

        ShippingAddressResponse shippingAddress = new ShippingAddressResponse(
                detail.shipRecipient(),
                detail.shipPhone(),
                detail.shipPostcode(),
                detail.shipAddress1(),
                detail.shipAddress2()
        );

        return new OrderResponse(
                detail.orderId(),
                detail.orderNumber(),
                detail.status(),
                itemResponses,
                detail.itemsAmount(),
                detail.discountAmount(),
                detail.shippingFee(),
                detail.finalAmount(),
                shippingAddress,
                detail.createdAt()
        );
    }

    /**
     * OrderSummary(내부 타입) → OrderSummaryResponse(응답 DTO).
     */
    OrderSummaryResponse toOrderSummaryResponse(OrderSummary summary) {
        return new OrderSummaryResponse(
                summary.orderId(),
                summary.orderNumber(),
                summary.status(),
                summary.representativeItemName(),
                summary.itemCount(),
                summary.finalAmount(),
                summary.createdAt()
        );
    }

    private OrderItemResponse toOrderItemResponse(OrderItemDetail item) {
        List<OrderItemOptionValueResponse> ovResponses = item.optionValues().stream()
                .map(this::toOrderItemOptionValueResponse)
                .toList();

        return new OrderItemResponse(
                item.itemId(),
                item.variantId(),
                item.productName(),
                item.optionLabel(),
                ovResponses,
                item.unitPrice(),
                item.quantity(),
                item.lineAmount()
        );
    }

    private OrderItemOptionValueResponse toOrderItemOptionValueResponse(OrderOptionValueDetail ov) {
        return new OrderItemOptionValueResponse(ov.optionName(), ov.optionValue(), ov.sortOrder());
    }
}
