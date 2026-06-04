package com.shop.shop.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * shop-core V1 마이그레이션 스크립트 정적 검증.
 *
 * DB 없이 클래스패스의 V1 리소스를 읽어 필수 토큰 존재를 검증한다.
 * - V1 불변 규칙을 지키므로 이 테스트는 파일 존재/내용 토큰만 확인한다.
 * - 실제 SQL 실행 검증은 docker-compose 로컬 Postgres + 앱 기동으로 수행한다 (plan §5.2).
 */
class FlywayMigrationScriptTest {

    private static final String V1_PATH = "db/migration/V1__init_schema.sql";
    private static String script;

    @BeforeAll
    static void loadScript() throws IOException {
        InputStream is = FlywayMigrationScriptTest.class
                .getClassLoader()
                .getResourceAsStream(V1_PATH);
        assertThat(is)
                .as("V1 마이그레이션 파일이 클래스패스에 존재해야 한다: %s", V1_PATH)
                .isNotNull();
        script = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("citext 확장 포함")
    void contains_citext_extension() {
        assertThat(script).containsIgnoringCase("citext");
    }

    @Test
    @DisplayName("event_publication 테이블 포함 (Modulith Outbox)")
    void contains_event_publication_table() {
        assertThat(script).contains("event_publication");
    }

    @Test
    @DisplayName("set_updated_at 트리거 함수 포함")
    void contains_set_updated_at_function() {
        assertThat(script).contains("set_updated_at");
    }

    @Test
    @DisplayName("도메인 핵심 테이블 포함 — users, products, orders, payments, reviews")
    void contains_core_domain_tables() {
        assertThat(script)
                .contains("CREATE TABLE users")
                .contains("CREATE TABLE products")
                .contains("CREATE TABLE orders")
                .contains("CREATE TABLE payments")
                .contains("CREATE TABLE reviews");
    }

    @Test
    @DisplayName("partial unique index 포함 — 기본 배송지 / 대표 이미지")
    void contains_partial_unique_indexes() {
        // addresses.is_default 부분 유니크
        assertThat(script).containsIgnoringCase("WHERE is_default");
        // product_images.is_primary 부분 유니크
        assertThat(script).containsIgnoringCase("WHERE is_primary");
    }

    @Test
    @DisplayName("V1 파일명이 Flyway 베이스라인 네이밍 규칙을 따른다")
    void v1_filename_follows_flyway_naming_convention() {
        // 로드에 성공했으면 파일명 규칙이 맞다는 의미 (V1__init_schema.sql)
        assertThat(script).isNotBlank();
    }
}
