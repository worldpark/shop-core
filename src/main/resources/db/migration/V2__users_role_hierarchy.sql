-- =============================================================================
-- V2__users_role_hierarchy.sql — users.role CHECK 제약 교체
-- =============================================================================
-- [V1 불변 규칙 준수]
-- V1__init_schema.sql은 Flyway 체크섬으로 보호된다. 수정 금지.
-- 이 파일(V2)이 V1의 users.role 제약을 대체한다.
--
-- [Deviation 사유 — docs/entity/database_design.md §4.1 와 다른 이유]
-- V1은 database_design.md §4.1 초안 기준(customer/admin)으로 작성되었다.
-- Task 006에서 권한 계층(ROLE_ADMIN > ROLE_SELLER > ROLE_CONSUMER)을 도입하면서
-- role 값이 ADMIN/SELLER/CONSUMER(대문자, @Enumerated(STRING) Java enum 상수명)로 변경되었다.
-- V1은 적용 후 불변(checksum)이므로 이 V2 마이그레이션으로 처리한다.
-- 변경 내용:
--   - CHECK: customer/admin → CONSUMER/SELLER/ADMIN
--   - DEFAULT: customer → CONSUMER
--   - 기존 행: customer→CONSUMER, admin→ADMIN (seller는 V1에 없으므로 변환 없음)
-- =============================================================================

-- Step 1: 기존 CHECK 제약 제거
-- (제약명은 V1__init_schema.sql에서 명명되지 않아 PostgreSQL 자동 생성명)
-- 자동 생성명이 환경마다 다를 수 있으므로 컬럼 재정의 방식 사용 (하단 주석 참조)
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;

-- Step 2: DEFAULT 제거
ALTER TABLE users ALTER COLUMN role DROP DEFAULT;

-- Step 3: 기존 행 값 변환 (V1 데이터가 있는 경우 안전 처리)
UPDATE users SET role = 'CONSUMER' WHERE role = 'customer';
UPDATE users SET role = 'ADMIN'    WHERE role = 'admin';

-- Step 4: 새 DEFAULT 설정 (CONSUMER)
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'CONSUMER';

-- Step 5: 새 CHECK 제약 추가
-- @Enumerated(EnumType.STRING) Java enum Role{CONSUMER, SELLER, ADMIN} 상수명과 1:1 매핑
ALTER TABLE users
    ADD CONSTRAINT users_role_check
    CHECK (role IN ('ADMIN', 'SELLER', 'CONSUMER'));
