-- =============================================================================
-- V5__seller_application.sql — 판매자 신청 워크플로우 (Task 027)
-- =============================================================================
-- [V5 불변 규칙]
-- 이 파일은 Flyway 체크섬으로 보호된다. 적용 후 절대 수정하지 않는다.
-- V1~V4 변경 없음.
--
-- [배경]
-- CONSUMER가 판매자(SELLER) 권한을 신청하고, ADMIN이 심사(승인/반려)하는 워크플로우.
-- 승인 시 신청자가 SELLER로 승격되며 이 레코드가 감사(audit) 기록이 된다.
-- 별도 감사 테이블 없음 — reviewedBy/decidedAt/status/rejectReason이 감사 정보.
--
-- [중복 신청 차단]
-- 사용자당 PENDING 신청은 최대 1건. 부분 유니크 인덱스(WHERE status='PENDING')로 DB 권위 가드.
-- REJECTED/APPROVED는 인덱스 대상 아님 → REJECTED 후 재신청 허용.
-- =============================================================================

-- -------------------------------------------------------------------------
-- seller_application: 판매자 신청 테이블
-- -------------------------------------------------------------------------
CREATE TABLE seller_application (
    id                            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                       bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                        varchar(20)  NOT NULL
                                  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    business_name                 text         NOT NULL,
    business_registration_number  text         NOT NULL,
    contact_phone                 text         NOT NULL,
    reject_reason                 text,
    reviewed_by                   bigint       REFERENCES users (id) ON DELETE SET NULL,
    decided_at                    timestamptz,
    created_at                    timestamptz  NOT NULL DEFAULT now(),
    updated_at                    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_seller_application_user_id ON seller_application (user_id);
CREATE INDEX idx_seller_application_status  ON seller_application (status);

-- 사용자당 PENDING 1건 (부분 유니크 — V1 line 84/140 선례). REJECTED/APPROVED 후 재신청 허용.
CREATE UNIQUE INDEX uq_seller_application_pending
    ON seller_application (user_id) WHERE status = 'PENDING';

-- updated_at 자동 갱신 (BaseEntity가 updatable=false로 매핑 → JPA 미기록 → 트리거 필수, V4 선례)
-- V1의 기존 set_updated_at() 함수를 재사용한다 (함수 재정의 불필요).
CREATE TRIGGER trg_seller_application_set_updated_at
    BEFORE UPDATE ON seller_application
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
