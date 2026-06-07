package com.shop.shop.order.dto;

/**
 * 배송지 스냅샷 응답 DTO.
 *
 * <p>주문 시점 배송지 스냅샷. 이후 변경 없음.
 */
public record ShippingAddressResponse(
        String recipient,
        String phone,
        String postcode,
        String address1,
        String address2
) {
}
