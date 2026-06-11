-- =============================================================================
-- V4__shipments.sql — 배송(Shipment) 스키마
-- =============================================================================
-- [V4 불변 규칙]
-- 이 파일은 Flyway 체크섬으로 보호된다. 적용 후 절대 수정하지 않는다.
-- 스키마 변경이 필요하면 V5__, V6__... 새 파일을 추가한다.
--
-- [배경]
-- 주문 이행(fulfillment)을 배송(Shipment) 단위로 모델링한다.
-- 한 주문(Order)은 여러 배송(Shipment)으로 나뉠 수 있으며,
-- 각 주문 항목(OrderItem)은 최대 1개 배송에만 속한다.
--
-- [이음매]
-- - seller_id: nullable — backlog 002에서 판매자 범위 이행을 켜기 위한 이음매 (본 Task 미사용)
-- - status CHECK: preparing/shipping/delivered 세 값 모두 포함 — 020/021 migration 재변경 불필요
-- - carrier/tracking_number/shipped_at/delivered_at: nullable — 020/021에서 사용
-- =============================================================================

-- -------------------------------------------------------------------------
-- shipments: 배송 단위. 주문(orders)의 일부 또는 전부 항목을 묶어 발송하는 단위.
-- -------------------------------------------------------------------------
CREATE TABLE shipments (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        bigint       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    seller_id       bigint,                         -- nullable 이음매 (backlog 002, 본 Task 미사용)
    status          varchar(20)  NOT NULL
                    CHECK (status IN ('preparing', 'shipping', 'delivered')),  -- 세 값 모두 포함 (020/021 재변경 불필요)
    carrier         text,                           -- nullable (020에서 사용)
    tracking_number text,                           -- nullable (020에서 사용)
    shipped_at      timestamptz,                    -- nullable (020에서 사용)
    delivered_at    timestamptz,                    -- nullable (021에서 사용)
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_shipments_order_id ON shipments (order_id);

-- updated_at 자동 갱신 트리거 (모순1 필수):
-- BaseEntity가 updated_at을 updatable=false로 매핑하므로 JPA가 절대 쓰지 않는다.
-- 트리거 없으면 020/021 상태 전이(UPDATE)에서 updated_at이 영원히 stale.
-- V1의 기존 set_updated_at() 함수를 재사용한다 (함수 재정의 불필요).
CREATE TRIGGER trg_shipments_set_updated_at
    BEFORE UPDATE ON shipments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------
-- shipment_items: 배송 항목. 배송(shipments)과 주문 항목(order_items) 간 매핑.
-- order_item_id UNIQUE: 한 주문 항목은 최대 1개 배송에만 속한다.
-- updated_at 없음 (불변·append) → 트리거 불필요.
-- -------------------------------------------------------------------------
CREATE TABLE shipment_items (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id   bigint NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    order_item_id bigint NOT NULL REFERENCES order_items (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_shipment_items_order_item UNIQUE (order_item_id)   -- 한 주문 항목은 최대 1개 배송
);

CREATE INDEX idx_shipment_items_shipment_id ON shipment_items (shipment_id);
