-- =============================================================================
-- V1__init_schema.sql — shop-core 초기 스키마
-- =============================================================================
-- [V1 불변 규칙]
-- 이 파일은 Flyway 체크섬으로 보호된다. 적용 후 절대 수정하지 않는다.
-- 스키마 변경이 필요하면 V2__, V3__... 새 파일을 추가한다 (checksum 불일치 방지).
--
-- [정본 출처]
-- docs/entity/database_design.md (도메인 18테이블 + Outbox 1테이블 = 19테이블)
--
-- [스키마 소유권]
-- Flyway가 단독 소유. Hibernate는 ddl-auto=validate로 검증만 수행한다.
-- Spring Modulith JDBC 스키마 자동 초기화는 비활성(application.yml 참조).
-- event_publication 테이블도 이 파일에서 Flyway가 생성한다.
--
-- [컨벤션]
-- - PK: bigint GENERATED ALWAYS AS IDENTITY (event_publication은 uuid — 프레임워크 표준)
-- - 시각: timestamptz
-- - 금액: numeric(12,2)
-- - 상태값: varchar + CHECK (Hibernate @Enumerated(EnumType.STRING)과 매핑)
-- - updated_at 자동 갱신: set_updated_at() 트리거 함수 + 테이블별 트리거
-- =============================================================================

-- -------------------------------------------------------------------------
-- 0. 확장: citext (이메일 대소문자 무시)
-- -------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS citext;

-- -------------------------------------------------------------------------
-- 1. updated_at 자동 갱신 트리거 함수
--    updated_at을 보유한 모든 테이블에 공유로 사용한다.
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
    RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- 회원 · 인증
-- =============================================================================

-- -------------------------------------------------------------------------
-- users: 회원 계정. role로 고객/관리자 구분
-- -------------------------------------------------------------------------
CREATE TABLE users (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         citext       NOT NULL,
    password_hash text         NOT NULL,
    name          text         NOT NULL,
    phone         text,
    role          varchar(20)  NOT NULL
                  CHECK (role IN ('customer', 'admin'))
                  DEFAULT 'customer',
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------
-- addresses: 회원 배송지. 사용자당 기본 배송지는 1개만 허용 (partial unique index)
-- -------------------------------------------------------------------------
CREATE TABLE addresses (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint      NOT NULL
               REFERENCES users (id) ON DELETE CASCADE,
    recipient  text        NOT NULL,
    phone      text        NOT NULL,
    postcode   text        NOT NULL,
    address1   text        NOT NULL,
    address2   text,
    is_default boolean     NOT NULL DEFAULT false
);

CREATE INDEX idx_addresses_user_id ON addresses (user_id);
-- 기본 배송지 1개 제약: is_default=true인 행은 user_id당 1개만
CREATE UNIQUE INDEX uq_addresses_user_default ON addresses (user_id) WHERE is_default;

-- =============================================================================
-- 카탈로그
-- =============================================================================

-- -------------------------------------------------------------------------
-- categories: 카테고리 트리 (parent_id 자기참조)
-- -------------------------------------------------------------------------
CREATE TABLE categories (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id  bigint REFERENCES categories (id) ON DELETE SET NULL,
    name       text   NOT NULL,
    slug       text   NOT NULL,
    sort_order int    NOT NULL DEFAULT 0,

    CONSTRAINT uq_categories_slug UNIQUE (slug)
);

-- -------------------------------------------------------------------------
-- products: 상품 기본 정보. 재고/가격은 product_variants에 있다.
-- -------------------------------------------------------------------------
CREATE TABLE products (
    id          bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id bigint         REFERENCES categories (id) ON DELETE SET NULL,
    name        text           NOT NULL,
    description text,
    base_price  numeric(12, 2) NOT NULL
                CHECK (base_price >= 0),
    status      varchar(20)    NOT NULL
                CHECK (status IN ('draft', 'on_sale', 'sold_out', 'hidden')),
    created_at  timestamptz    NOT NULL DEFAULT now(),
    updated_at  timestamptz    NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_category_id ON products (category_id);
CREATE INDEX idx_products_status ON products (status);

CREATE TRIGGER trg_products_set_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------
-- product_images: 상품 이미지. 대표 이미지는 상품당 1개 (partial unique index)
-- -------------------------------------------------------------------------
CREATE TABLE product_images (
    id          bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id  bigint  NOT NULL
                REFERENCES products (id) ON DELETE CASCADE,
    storage_key text    NOT NULL,
    sort_order  int     NOT NULL DEFAULT 0,
    is_primary  boolean NOT NULL DEFAULT false
);

CREATE INDEX idx_product_images_product_id ON product_images (product_id);
-- 대표 이미지 1개 제약: is_primary=true인 행은 product_id당 1개만
CREATE UNIQUE INDEX uq_product_images_primary ON product_images (product_id) WHERE is_primary;

-- -------------------------------------------------------------------------
-- product_options: 옵션 종류 (예: 색상, 사이즈)
-- -------------------------------------------------------------------------
CREATE TABLE product_options (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id bigint NOT NULL
               REFERENCES products (id) ON DELETE CASCADE,
    name       text   NOT NULL,

    CONSTRAINT uq_product_options_product_name UNIQUE (product_id, name)
);

-- -------------------------------------------------------------------------
-- option_values: 옵션 값 (예: 빨강, 파랑 / S, M, L)
-- -------------------------------------------------------------------------
CREATE TABLE option_values (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    option_id bigint NOT NULL
              REFERENCES product_options (id) ON DELETE CASCADE,
    value     text   NOT NULL,

    CONSTRAINT uq_option_values_option_value UNIQUE (option_id, value)
);

-- -------------------------------------------------------------------------
-- product_variants: 실제 구매 단위 (SKU). 재고와 가격을 가진다.
-- -------------------------------------------------------------------------
CREATE TABLE product_variants (
    id         bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id bigint         NOT NULL
               REFERENCES products (id) ON DELETE CASCADE,
    sku        text           NOT NULL,
    price      numeric(12, 2) NOT NULL
               CHECK (price >= 0),
    stock      int            NOT NULL DEFAULT 0
               CHECK (stock >= 0),
    is_active  boolean        NOT NULL DEFAULT true,
    created_at timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT uq_product_variants_sku UNIQUE (sku)
);

CREATE INDEX idx_product_variants_product_id ON product_variants (product_id);

-- -------------------------------------------------------------------------
-- variant_values: variant ↔ 옵션값 매핑 (조인 테이블, 복합 PK)
-- -------------------------------------------------------------------------
CREATE TABLE variant_values (
    variant_id      bigint NOT NULL
                    REFERENCES product_variants (id) ON DELETE CASCADE,
    option_value_id bigint NOT NULL
                    REFERENCES option_values (id) ON DELETE CASCADE,

    CONSTRAINT pk_variant_values PRIMARY KEY (variant_id, option_value_id)
);

-- =============================================================================
-- 장바구니
-- =============================================================================

-- -------------------------------------------------------------------------
-- carts: 회원당 1개
-- -------------------------------------------------------------------------
CREATE TABLE carts (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint      NOT NULL
               REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_carts_user_id UNIQUE (user_id)
);

CREATE TRIGGER trg_carts_set_updated_at
    BEFORE UPDATE ON carts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------
-- cart_items: 장바구니 항목
-- -------------------------------------------------------------------------
CREATE TABLE cart_items (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id    bigint      NOT NULL
               REFERENCES carts (id) ON DELETE CASCADE,
    variant_id bigint      NOT NULL
               REFERENCES product_variants (id) ON DELETE CASCADE,
    quantity   int         NOT NULL
               CHECK (quantity > 0),
    added_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_cart_items_cart_variant UNIQUE (cart_id, variant_id)
);

-- =============================================================================
-- 주문 · 결제
-- =============================================================================

-- -------------------------------------------------------------------------
-- orders: 주문 헤더. 금액과 배송지는 주문 시점 스냅샷이다.
-- -------------------------------------------------------------------------
CREATE TABLE orders (
    id              bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         bigint         NOT NULL
                    REFERENCES users (id) ON DELETE RESTRICT,
    order_number    text           NOT NULL,
    status          varchar(20)    NOT NULL
                    CHECK (status IN ('pending', 'paid', 'preparing', 'shipping', 'delivered', 'cancelled', 'refunded')),
    items_amount    numeric(12, 2) NOT NULL
                    CHECK (items_amount >= 0),
    discount_amount numeric(12, 2) NOT NULL DEFAULT 0,
    shipping_fee    numeric(12, 2) NOT NULL DEFAULT 0,
    final_amount    numeric(12, 2) NOT NULL
                    CHECK (final_amount >= 0),
    -- 배송지 스냅샷 (address 변경과 무관하게 불변)
    ship_recipient  text,
    ship_phone      text,
    ship_postcode   text,
    ship_address1   text,
    ship_address2   text,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT uq_orders_order_number UNIQUE (order_number)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at);

CREATE TRIGGER trg_orders_set_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------
-- order_items: 주문 항목. variant_id는 참조용(nullable), 표시값은 스냅샷
-- -------------------------------------------------------------------------
CREATE TABLE order_items (
    id           bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     bigint         NOT NULL
                 REFERENCES orders (id) ON DELETE CASCADE,
    variant_id   bigint         REFERENCES product_variants (id) ON DELETE SET NULL,
    product_name text           NOT NULL,
    option_label text,
    unit_price   numeric(12, 2) NOT NULL
                 CHECK (unit_price >= 0),
    quantity     int            NOT NULL
                 CHECK (quantity > 0),
    line_amount  numeric(12, 2) NOT NULL
                 CHECK (line_amount >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_variant_id ON order_items (variant_id);

-- -------------------------------------------------------------------------
-- order_item_option_values: 주문 항목의 옵션값 스냅샷
-- -------------------------------------------------------------------------
CREATE TABLE order_item_option_values (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_item_id bigint NOT NULL
                  REFERENCES order_items (id) ON DELETE CASCADE,
    option_name   text   NOT NULL,
    option_value  text   NOT NULL,
    sort_order    int    NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_item_option_values_order_item_id ON order_item_option_values (order_item_id);
CREATE INDEX idx_order_item_option_values_name_value ON order_item_option_values (option_name, option_value);

-- -------------------------------------------------------------------------
-- payments: 주문과 1:1. 현재 결제는 mock이지만 실제 PG 연동을 가정한 구조
-- -------------------------------------------------------------------------
CREATE TABLE payments (
    id                bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id          bigint         NOT NULL
                      REFERENCES orders (id) ON DELETE RESTRICT,
    method            varchar(20)    NOT NULL
                      CHECK (method IN ('card', 'bank_transfer', 'virtual_account', 'mock')),
    status            varchar(20)    NOT NULL
                      CHECK (status IN ('ready', 'paid', 'failed', 'cancelled', 'refunded')),
    amount            numeric(12, 2) NOT NULL
                      CHECK (amount >= 0),
    pg_transaction_id text,
    paid_at           timestamptz,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    updated_at        timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT uq_payments_order_id UNIQUE (order_id)
);

CREATE TRIGGER trg_payments_set_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =============================================================================
-- 쿠폰
-- =============================================================================

-- -------------------------------------------------------------------------
-- coupons: 쿠폰 정의 (규칙)
-- -------------------------------------------------------------------------
CREATE TABLE coupons (
    id               bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             text           NOT NULL,
    name             text           NOT NULL,
    discount_type    varchar(10)    NOT NULL
                     CHECK (discount_type IN ('fixed', 'percent')),
    value            numeric(12, 2) NOT NULL
                     CHECK (value > 0),
    min_order_amount numeric(12, 2) NOT NULL DEFAULT 0,
    max_discount     numeric(12, 2),
    starts_at        timestamptz    NOT NULL,
    ends_at          timestamptz    NOT NULL,
    usage_limit      int,
    used_count       int            NOT NULL DEFAULT 0,
    is_active        boolean        NOT NULL DEFAULT true,

    CONSTRAINT uq_coupons_code UNIQUE (code),
    CONSTRAINT chk_coupons_ends_after_starts CHECK (ends_at > starts_at)
);

-- -------------------------------------------------------------------------
-- user_coupons: 사용자에게 발급된 쿠폰 / 사용 내역
--   order_id / used_at이 NULL이면 미사용(쿠폰함 보유) 상태
-- -------------------------------------------------------------------------
CREATE TABLE user_coupons (
    id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id   bigint      NOT NULL
              REFERENCES users (id) ON DELETE CASCADE,
    coupon_id bigint      NOT NULL
              REFERENCES coupons (id) ON DELETE CASCADE,
    order_id  bigint      REFERENCES orders (id) ON DELETE SET NULL,
    issued_at timestamptz NOT NULL,
    used_at   timestamptz,

    CONSTRAINT uq_user_coupons_user_coupon UNIQUE (user_id, coupon_id)
);

CREATE INDEX idx_user_coupons_user_id ON user_coupons (user_id);
-- 미사용 쿠폰 조회 최적화: used_at IS NULL인 행만 인덱스
CREATE INDEX idx_user_coupons_user_unused ON user_coupons (user_id) WHERE used_at IS NULL;

-- =============================================================================
-- 리뷰
-- =============================================================================

-- -------------------------------------------------------------------------
-- reviews: 상품 리뷰. order_item_id로 실구매를 검증하고 UNIQUE로 1건당 1리뷰 보장
-- -------------------------------------------------------------------------
CREATE TABLE reviews (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id    bigint      NOT NULL
                  REFERENCES products (id) ON DELETE CASCADE,
    user_id       bigint      NOT NULL
                  REFERENCES users (id) ON DELETE CASCADE,
    order_item_id bigint      NOT NULL
                  REFERENCES order_items (id) ON DELETE RESTRICT,
    rating        smallint    NOT NULL
                  CHECK (rating BETWEEN 1 AND 5),
    content       text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_reviews_order_item_id UNIQUE (order_item_id)
);

CREATE INDEX idx_reviews_product_id ON reviews (product_id);
CREATE INDEX idx_reviews_user_id ON reviews (user_id);

CREATE TRIGGER trg_reviews_set_updated_at
    BEFORE UPDATE ON reviews
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =============================================================================
-- 인프라 — Transactional Outbox (Spring Modulith Event Publication Registry)
-- =============================================================================

-- -------------------------------------------------------------------------
-- event_publication
-- Spring Modulith JPA Event Publication Registry가 사용하는 테이블.
-- Flyway가 소유하며, Modulith 자동 초기화에 맡기지 않는다.
--
-- 테이블명 근거:
--   DefaultJpaEventPublication @Table(name="EVENT_PUBLICATION")
--   Spring Boot SpringPhysicalNamingStrategy가 EVENT_PUBLICATION → event_publication으로 변환
--
-- 컬럼 매핑 근거 (spring-modulith-events-jpa:1.3.1 JpaEventPublication.class 직접 확인):
--   id              uuid    @Id @Column(length=16)  — uuid PK (bigint identity 예외)
--   publication_date timestamptz NOT NULL            — Instant publicationDate
--   listener_id     text NOT NULL                   — String listenerId
--   serialized_event text NOT NULL                  — String serializedEvent
--   event_type      text NOT NULL                   — Class<?> eventType (AttributeConverter로 String 저장)
--   completion_date  timestamptz NULL               — Instant completionDate (NULL=미완료)
--
-- 미완료 행(completion_date IS NULL)은 재시도 대상이며,
-- 완료 행은 보존 정책에 따라 주기적으로 정리할 수 있다.
-- -------------------------------------------------------------------------
CREATE TABLE event_publication (
    id               uuid        NOT NULL PRIMARY KEY,
    publication_date timestamptz NOT NULL,
    listener_id      text        NOT NULL,
    serialized_event text        NOT NULL,
    event_type       text        NOT NULL,
    completion_date  timestamptz
);
