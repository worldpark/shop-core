-- =============================================================================
-- V3__product_status_and_owner.sql — products.status 대문자 정합 + owner_id 추가
-- =============================================================================
-- [V1/V2 불변 규칙 준수]
-- V1·V2는 Flyway 체크섬으로 보호된다. 수정 금지.
-- 이 V3 파일이 필요한 변경을 담당한다.
--
-- [변경 사유]
-- 1) products.status CHECK 제약이 V1에서 소문자('draft','on_sale','sold_out','hidden')로 정의됨.
--    Java @Enumerated(EnumType.STRING)은 대문자 상수명을 저장하므로 불일치 → 대문자로 교체.
--    (V2 users.role 대문자 교체 패턴 계승)
-- 2) products 테이블에 판매자/소유자 식별 컬럼(owner_id)이 없어 소유권 검사 불가 → V3에서 추가.
-- =============================================================================

-- 1) status CHECK 대문자 교체 (V2 users.role 패턴 계승)
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_status_check;
UPDATE products SET status = upper(status);
ALTER TABLE products
    ADD CONSTRAINT products_status_check
    CHECK (status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'HIDDEN'));
-- NOTE: DEFAULT는 V1에 없음 → 등록 기본 DRAFT는 애플리케이션(ProductService.create)이 강제

-- 2) owner_id 추가 (판매자/소유자 식별 — users.id 참조)
-- nullable: V1 데이터 유무 불문 안전하게 추가. 앱이 등록 시 항상 채움.
ALTER TABLE products
    ADD COLUMN owner_id bigint REFERENCES users (id) ON DELETE RESTRICT;
CREATE INDEX idx_products_owner_id ON products (owner_id);
