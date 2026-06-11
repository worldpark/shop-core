package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipment 도메인 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>{@link Shipment#preparing(long)}: status=preparing, 추적 필드 모두 null</li>
 *   <li>{@link Shipment#addItem(ShipmentItem)}: 양방향 연관 세팅</li>
 *   <li>{@link ShipmentItem#of(long)}: orderItemId 세팅</li>
 * </ul>
 */
class ShipmentTest {

    @Test
    @DisplayName("Shipment.preparing: status=preparing, orderId 세팅")
    void preparing_setsStatusAndOrderId() {
        Shipment shipment = Shipment.preparing(42L);

        assertThat(shipment.getOrderId()).isEqualTo(42L);
        assertThat(shipment.getStatus()).isEqualTo("preparing");
    }

    @Test
    @DisplayName("Shipment.preparing: 추적 필드(carrier/trackingNumber/shippedAt/deliveredAt) 모두 null")
    void preparing_tracingFieldsAreNull() {
        Shipment shipment = Shipment.preparing(1L);

        assertThat(shipment.getCarrier()).isNull();
        assertThat(shipment.getTrackingNumber()).isNull();
        assertThat(shipment.getShippedAt()).isNull();
        assertThat(shipment.getDeliveredAt()).isNull();
    }

    @Test
    @DisplayName("Shipment.preparing: sellerId null (nullable 이음매)")
    void preparing_sellerIdIsNull() {
        Shipment shipment = Shipment.preparing(1L);

        assertThat(shipment.getSellerId()).isNull();
    }

    @Test
    @DisplayName("Shipment.preparing: items 빈 목록")
    void preparing_itemsIsEmpty() {
        Shipment shipment = Shipment.preparing(1L);

        assertThat(shipment.getItems()).isEmpty();
    }

    @Test
    @DisplayName("Shipment.addItem: ShipmentItem 추가 + 양방향 연관 세팅")
    void addItem_setsRelationship() {
        Shipment shipment = Shipment.preparing(1L);
        ShipmentItem item = ShipmentItem.of(100L);

        shipment.addItem(item);

        assertThat(shipment.getItems()).hasSize(1);
        assertThat(shipment.getItems().get(0)).isSameAs(item);
        assertThat(item.getShipment()).isSameAs(shipment);
        assertThat(item.getOrderItemId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Shipment.addItem: 여러 항목 추가")
    void addItem_multipleItems() {
        Shipment shipment = Shipment.preparing(1L);
        ShipmentItem item1 = ShipmentItem.of(10L);
        ShipmentItem item2 = ShipmentItem.of(20L);

        shipment.addItem(item1);
        shipment.addItem(item2);

        assertThat(shipment.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("ShipmentItem.of: orderItemId 세팅")
    void shipmentItemOf_setsOrderItemId() {
        ShipmentItem item = ShipmentItem.of(999L);

        assertThat(item.getOrderItemId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("ShipmentItem.of: shipment 참조는 null (addItem 전)")
    void shipmentItemOf_shipmentIsNullBeforeAddItem() {
        ShipmentItem item = ShipmentItem.of(1L);

        assertThat(item.getShipment()).isNull();
    }
}
