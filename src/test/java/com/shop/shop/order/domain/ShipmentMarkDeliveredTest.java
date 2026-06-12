package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shipment.markDelivered() 단위 테스트 (Task 021).
 *
 * <p>검증:
 * <ul>
 *   <li>shipping → delivered 전이 성공: status=delivered, deliveredAt 기록</li>
 *   <li>shipping 이외 상태(preparing/delivered 재호출/역방향) → IllegalStateException</li>
 *   <li>멱등 없음(정합4) — 도메인 메서드는 단일 전이만, 멱등 책임은 서비스 소유</li>
 * </ul>
 */
class ShipmentMarkDeliveredTest {

    private static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");

    // ============================================================
    // 성공 — shipping → delivered
    // ============================================================

    @Test
    @DisplayName("markDelivered: shipping → delivered 전이 성공, status=delivered")
    void markDelivered_fromShipping_statusIsDelivered() {
        Shipment shipment = shippingShipment();

        shipment.markDelivered(NOW);

        assertThat(shipment.getStatus()).isEqualTo("delivered");
    }

    @Test
    @DisplayName("markDelivered: deliveredAt 설정됨")
    void markDelivered_fromShipping_deliveredAtSet() {
        Shipment shipment = shippingShipment();

        shipment.markDelivered(NOW);

        assertThat(shipment.getDeliveredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("markDelivered: carrier/trackingNumber/shippedAt은 변경 안 됨")
    void markDelivered_fromShipping_trackingInfoUnchanged() {
        Shipment shipment = shippingShipment();

        shipment.markDelivered(NOW);

        assertThat(shipment.getCarrier()).isEqualTo("CJ대한통운");
        assertThat(shipment.getTrackingNumber()).isEqualTo("TRK-001");
        assertThat(shipment.getShippedAt()).isNotNull();
    }

    // ============================================================
    // 실패 — shipping 이외 상태
    // ============================================================

    @Test
    @DisplayName("markDelivered: preparing 상태 → IllegalStateException")
    void markDelivered_fromPreparing_throwsIllegalStateException() {
        Shipment shipment = Shipment.preparing(1L);

        assertThatThrownBy(() -> shipment.markDelivered(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shipping");
    }

    @Test
    @DisplayName("markDelivered: 이미 delivered → IllegalStateException (멱등 없음, 서비스가 skip 처리, 정합4)")
    void markDelivered_alreadyDelivered_throwsIllegalStateException() {
        Shipment shipment = shippingShipment();
        shipment.markDelivered(NOW);
        assertThat(shipment.getStatus()).isEqualTo("delivered");

        assertThatThrownBy(() -> shipment.markDelivered(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivered");
    }

    @Test
    @DisplayName("markDelivered: 오류 메시지에 현재 상태 포함")
    void markDelivered_errorMessageContainsCurrentStatus() {
        Shipment shipment = Shipment.preparing(1L);

        assertThatThrownBy(() -> shipment.markDelivered(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preparing");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    /** shipping 상태의 Shipment 생성 */
    private Shipment shippingShipment() {
        Shipment shipment = Shipment.preparing(1L);
        shipment.markShipping("CJ대한통운", "TRK-001", Instant.parse("2026-06-11T10:00:00Z"));
        return shipment;
    }
}
