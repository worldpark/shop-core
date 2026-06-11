package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shipment.markShipping() 단위 테스트 (Task 020).
 *
 * <p>검증:
 * <ul>
 *   <li>preparing → shipping 전이 성공: status/carrier/trackingNumber/shippedAt 설정</li>
 *   <li>preparing 이외 상태(shipping/delivered 등) → IllegalStateException</li>
 * </ul>
 */
class ShipmentMarkShippingTest {

    private static final Instant NOW = Instant.parse("2026-06-11T10:00:00Z");

    // ============================================================
    // 성공 — preparing → shipping
    // ============================================================

    @Test
    @DisplayName("markShipping: preparing → shipping 전이 성공, status=shipping")
    void markShipping_fromPreparing_statusIsShipping() {
        Shipment shipment = Shipment.preparing(1L);

        shipment.markShipping("CJ대한통운", "123456789", NOW);

        assertThat(shipment.getStatus()).isEqualTo("shipping");
    }

    @Test
    @DisplayName("markShipping: carrier 설정됨")
    void markShipping_fromPreparing_carrierSet() {
        Shipment shipment = Shipment.preparing(1L);

        shipment.markShipping("한진택배", "987654321", NOW);

        assertThat(shipment.getCarrier()).isEqualTo("한진택배");
    }

    @Test
    @DisplayName("markShipping: trackingNumber 설정됨")
    void markShipping_fromPreparing_trackingNumberSet() {
        Shipment shipment = Shipment.preparing(1L);

        shipment.markShipping("로젠택배", "TRK-001", NOW);

        assertThat(shipment.getTrackingNumber()).isEqualTo("TRK-001");
    }

    @Test
    @DisplayName("markShipping: shippedAt 설정됨")
    void markShipping_fromPreparing_shippedAtSet() {
        Shipment shipment = Shipment.preparing(1L);

        shipment.markShipping("CJ대한통운", "123", NOW);

        assertThat(shipment.getShippedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("markShipping: deliveredAt은 여전히 null (021 소관)")
    void markShipping_fromPreparing_deliveredAtStillNull() {
        Shipment shipment = Shipment.preparing(1L);

        shipment.markShipping("CJ대한통운", "123", NOW);

        assertThat(shipment.getDeliveredAt()).isNull();
    }

    // ============================================================
    // 실패 — preparing 이외 상태
    // ============================================================

    @Test
    @DisplayName("markShipping: 이미 shipping → IllegalStateException")
    void markShipping_alreadyShipping_throwsIllegalStateException() {
        Shipment shipment = Shipment.preparing(1L);
        shipment.markShipping("CJ대한통운", "123", NOW);
        assertThat(shipment.getStatus()).isEqualTo("shipping");

        assertThatThrownBy(() -> shipment.markShipping("한진택배", "456", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shipping");
    }

    @Test
    @DisplayName("markShipping: delivered 상태 → IllegalStateException (방어적)")
    void markShipping_fromDelivered_throwsIllegalStateException() {
        Shipment shipment = Shipment.preparing(1L);
        setStatus(shipment, "delivered");

        assertThatThrownBy(() -> shipment.markShipping("CJ대한통운", "123", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivered");
    }

    @Test
    @DisplayName("markShipping: cancelled 상태 → IllegalStateException (방어적)")
    void markShipping_fromCancelled_throwsIllegalStateException() {
        Shipment shipment = Shipment.preparing(1L);
        setStatus(shipment, "cancelled");

        assertThatThrownBy(() -> shipment.markShipping("CJ대한통운", "123", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markShipping: 오류 메시지에 현재 상태 포함")
    void markShipping_errorMessageContainsCurrentStatus() {
        Shipment shipment = Shipment.preparing(1L);
        shipment.markShipping("CJ대한통운", "123", NOW); // preparing → shipping

        assertThatThrownBy(() -> shipment.markShipping("한진택배", "456", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shipping");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void setStatus(Shipment shipment, String status) {
        try {
            java.lang.reflect.Field field = Shipment.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(shipment, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
