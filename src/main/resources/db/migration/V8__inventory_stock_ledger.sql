CREATE TABLE inventory_stock_ledger (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id      bigint      NOT NULL
                    REFERENCES product_variants (id) ON DELETE CASCADE,
    delta           int         NOT NULL,
    reason          varchar(20) NOT NULL
                    CHECK (reason IN ('ORDER_DECREASE','CANCEL_RESTORE','EXPIRY_RESTORE','ADJUSTMENT')),
    quantity_before int         NOT NULL CHECK (quantity_before >= 0),
    quantity_after  int         NOT NULL CHECK (quantity_after >= 0),
    actor_id        bigint      REFERENCES users (id) ON DELETE SET NULL,
    memo            text,
    occurred_at     timestamptz NOT NULL
);
CREATE INDEX idx_inventory_stock_ledger_variant_id ON inventory_stock_ledger (variant_id);
CREATE INDEX idx_inventory_stock_ledger_occurred_at ON inventory_stock_ledger (occurred_at);
