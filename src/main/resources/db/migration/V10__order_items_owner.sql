-- =============================================================================
-- V10__order_items_owner.sql — order_items.owner_id 판매자 스냅샷 컬럼 추가
-- =============================================================================
-- [V10 불변 규칙]
-- 이 파일은 Flyway 체크섬으로 보호된다. 적용 후 절대 수정하지 않는다.
-- 스키마 변경이 필요하면 V11__... 새 파일을 추가한다.
--
-- [배경]
-- 판매자 주문 조회(Phase 1)를 위한 order_items.owner_id 스칼라 스냅샷 추가.
-- owner_id = users.id (상품 소유자 = 판매자). 스칼라 스냅샷이므로 Entity 교차참조 없음.
-- 인덱스: WHERE owner_id = :sellerId 페이지네이션 쿼리 최적화.
--
-- [백필]
-- variant_id → product_variants → products.owner_id 조인으로 기존 행 채움.
-- variant가 SET NULL된 행(product_variants 행 삭제)은 owner_id NULL 잔존 허용 (스냅샷 손실 명시).
-- =============================================================================

-- -------------------------------------------------------------------------
-- 컬럼 추가
-- -------------------------------------------------------------------------
ALTER TABLE order_items
    ADD COLUMN owner_id BIGINT;

-- -------------------------------------------------------------------------
-- 인덱스 추가 (판매자 주문 목록 페이지네이션 최적화)
-- -------------------------------------------------------------------------
CREATE INDEX idx_order_items_owner_id ON order_items (owner_id);

-- -------------------------------------------------------------------------
-- 백필: variant_id → product_variants → products.owner_id
-- variant가 SET NULL된 행(variant_id IS NULL)은 owner_id NULL 잔존
-- -------------------------------------------------------------------------
UPDATE order_items oi
SET    owner_id = p.owner_id
FROM   product_variants pv
JOIN   products p ON pv.product_id = p.id
WHERE  oi.variant_id = pv.id;
