package com.shop.shop.common.crypto;

import com.shop.shop.order.domain.Order;
import com.shop.shop.order.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 봉투암호화 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>Order.ship_phone 등 PII 필드: JPA save → find → 엔티티 평문 복원</li>
 *   <li>DB raw 컬럼(ship_phone): 네이티브 쿼리로 조회 시 평문과 다르고 "v1:"으로 시작 (실제 암호문 확인)</li>
 *   <li>null 컬럼(ship_address2): null 저장 → null 복원 (null 통과)</li>
 *   <li>테스트 KEK(test-kek.b64 + test application.yml)가 통합 환경에서 정상 동작함을 실증</li>
 * </ul>
 *
 * <p>KEK 공급: test/resources/application.yml shop.crypto.kek-file → test-kek.b64 (고정 테스트 키).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class PiiEncryptionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Order 저장 → 조회 시 PII 필드(ship_phone 등) 평문 복원")
    void saveAndFind_orderShipFields_plainTextRestoredInEntity() {
        // given
        long userId = insertUser("pii-enc-test-1@test.com");
        String shipPhone = "010-9999-1234";
        String shipRecipient = "테스트수령인";
        String shipPostcode = "06123";
        String shipAddress1 = "서울시 강남구 테헤란로 123";
        String shipAddress2 = null; // nullable 필드 null 통과 검증

        Order order = Order.create(
                userId,
                "ORD-PIITEST-" + System.nanoTime(),
                BigDecimal.valueOf(10000),
                shipRecipient,
                shipPhone,
                shipPostcode,
                shipAddress1,
                shipAddress2
        );

        // when: JPA save (convertToDatabaseColumn 경유 — 암호화)
        Order saved = orderRepository.save(order);
        orderRepository.flush();

        // then: JPA find (convertToEntityAttribute 경유 — 복호화)
        Order found = orderRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getShipPhone()).isEqualTo(shipPhone);
        assertThat(found.getShipRecipient()).isEqualTo(shipRecipient);
        assertThat(found.getShipPostcode()).isEqualTo(shipPostcode);
        assertThat(found.getShipAddress1()).isEqualTo(shipAddress1);
        assertThat(found.getShipAddress2()).isNull();  // null 통과 검증
    }

    @Test
    @DisplayName("DB raw ship_phone 컬럼은 암호문 — 평문과 다르고 v1:으로 시작")
    void rawColumnValue_isEncrypted_startsWithV1Prefix() {
        // given
        long userId = insertUser("pii-enc-test-2@test.com");
        String plainPhone = "010-7777-8888";

        Order order = Order.create(
                userId,
                "ORD-RAWTEST-" + System.nanoTime(),
                BigDecimal.valueOf(5000),
                "수령인",
                plainPhone,
                "12345",
                "서울시 테스트구",
                null
        );

        // when: JPA save
        Order saved = orderRepository.save(order);
        orderRepository.flush();

        // then: 네이티브 쿼리로 raw 컬럼 조회 — 암호문이어야 함
        String rawShipPhone = jdbc.queryForObject(
                "SELECT ship_phone FROM orders WHERE id = ?",
                String.class,
                saved.getId()
        );

        assertThat(rawShipPhone)
                .as("DB raw ship_phone은 평문과 달라야 한다")
                .isNotEqualTo(plainPhone);
        assertThat(rawShipPhone)
                .as("DB raw ship_phone은 봉투암호화 포맷 v1:으로 시작해야 한다")
                .startsWith("v1:");
    }

    @Test
    @DisplayName("DB raw ship_address2 null 컬럼 — null 저장 → null 복원")
    void nullShipAddress2_storedAndRestoredAsNull() {
        // given
        long userId = insertUser("pii-enc-test-3@test.com");

        Order order = Order.create(
                userId,
                "ORD-NULLTEST-" + System.nanoTime(),
                BigDecimal.valueOf(3000),
                "수령인",
                "010-0000-0000",
                "99999",
                "주소1",
                null  // ship_address2 = null
        );

        // when
        Order saved = orderRepository.save(order);
        orderRepository.flush();

        // then: DB raw 컬럼도 null
        String rawAddress2 = jdbc.queryForObject(
                "SELECT ship_address2 FROM orders WHERE id = ?",
                String.class,
                saved.getId()
        );
        assertThat(rawAddress2).isNull();

        // JPA 엔티티도 null 복원
        Order found = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getShipAddress2()).isNull();
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        // name 컬럼은 암호화 컬럼이지만, 여기서는 userId 확보 목적으로만 사용하므로
        // raw INSERT로 임의 문자열 삽입. 이 테스트에서 User 엔티티를 JPA로 조회하지 않으므로
        // 복호화 에러 미발생.
        jdbc.update(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'hash', '테스터', 'CONSUMER')",
                email
        );
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
