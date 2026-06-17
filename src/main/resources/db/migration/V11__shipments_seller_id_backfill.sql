-- =============================================================================
-- V11__shipments_seller_id_backfill.sql — shipments.seller_id 레거시 백필
-- =============================================================================
-- [V4 불변 규칙 준수]
-- V4__shipments.sql은 Flyway 체크섬으로 보호된다. 이 파일은 V4의 seller_id 컬럼에
-- 대해 UPDATE만 수행한다 (DDL 변경 없음, Entity↔SQL 매핑 변경 없음).
--
-- [V10 불변 규칙 준수]
-- V10__order_items_owner.sql로 백필된 order_items.owner_id를 참조한다.
-- V10 파일 자체는 수정하지 않는다.
--
-- [배경 — 백필 목적]
-- Phase 2(Task 049) 이전 admin이 생성한 shipment는 seller_id=NULL로 잔존한다.
-- 이 배송들 중 shipment_items → order_items.owner_id 조인으로 판단했을 때
-- "전 항목이 동일 단일 소유자이고 owner 불명(NULL) 항목이 0건"인 경우에 한해 seller_id를 채운다.
--
-- [백필 한계 — NULL 유지 케이스]
-- 다음 경우는 seller_id를 NULL로 유지한다 (admin 전용 잔존 — seller가 못 건드림):
--   1. 혼합 소유자: COUNT(DISTINCT owner_id) > 1 (여러 판매자 항목 혼재)
--   2. 전체 NULL: 전 항목이 owner_id=NULL (owner 불명)
--   3. X+NULL 혼합: 일부는 owner_id=X, 나머지는 NULL — owner 불명 항목이 하나라도 있으면 X가
--      그 NULL 항목까지 조작 가능해지므로 백필 금지.
--      (★ COUNT(*)=COUNT(oi.owner_id) 조건으로 판별 — SQL COUNT는 NULL을 카운트하지 않음)
--
-- [의도된 권한 부수효과 — plan §1.3]
-- 백필로 seller_id=X가 된 배송은 해당 seller가 이후 ship/deliver 가능해진다
-- (SellerFulfillmentFacadeImpl.verifySellerOwnership 통과).
-- 이는 모순이 아니라 의도된 정합: 단일소유 배송은 본래 그 seller의 것이며
-- 레거시를 seller 스코프 모델로 편입하는 것이 이 마이그레이션의 목적이다.
-- =============================================================================

-- 전 항목이 동일 단일 소유자이고 owner 불명(NULL) 항목이 0건인 레거시 배송만 seller_id 백필.
-- 혼합 소유자·owner 불명 포함(X+NULL 혼합 포함)은 NULL 유지(admin 전용 잔존).
UPDATE shipments s
SET    seller_id = sub.owner_id
FROM (
    SELECT si.shipment_id, MIN(oi.owner_id) AS owner_id
    FROM   shipment_items si
    JOIN   order_items oi ON si.order_item_id = oi.id
    GROUP  BY si.shipment_id
    HAVING COUNT(DISTINCT oi.owner_id) = 1     -- 단일 소유자 (NULL 무시, non-null 값만 비교)
       AND COUNT(*) = COUNT(oi.owner_id)       -- owner 불명(NULL) 항목 0건 보장
) sub
WHERE s.id = sub.shipment_id
  AND s.seller_id IS NULL;                     -- 049 스탬프 행 불변 (이미 seller_id 있는 행 제외)
