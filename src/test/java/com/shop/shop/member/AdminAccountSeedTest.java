package com.shop.shop.member;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 개발 DB에 최초 ADMIN 계정을 심는 수동 실행용 테스트.
 *
 * <p>일반 테스트 실행에서는 비활성화된다. 필요할 때만
 * {@code -Dseed.admin.enabled=true}로 실행한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/shop_core",
        "spring.datasource.username=shop_core",
        "spring.datasource.password=shop_core",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.modulith.events.externalization.enabled=false",
        "shop.security.jwt.secret=test-secret-key-for-admin-seed-test-only-32chars",
        "shop.security.jwt.access-ttl=PT30M",
        "shop.security.jwt.refresh-ttl=P14D",
        "shop.security.jwt.issuer=shop-core"
})
@EnabledIfSystemProperty(named = "seed.admin.enabled", matches = "true")
class AdminAccountSeedTest {

    private static final String EMAIL = "admin@example.com";
    private static final String RAW_PASSWORD = "Admin1234!";
    private static final String NAME = "관리자";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EnvelopeEncryptionService crypto;

    @Test
    @Commit
    @Transactional
    @DisplayName("로컬 shop_core DB에 ADMIN 계정을 생성하거나 갱신한다")
    void seedAdminAccount() {
        String passwordHash = passwordEncoder.encode(RAW_PASSWORD);
        String encryptedName = crypto.encrypt(NAME);

        int changedRows = entityManager.createNativeQuery("""
                INSERT INTO users (email, password_hash, name, phone, role)
                VALUES (:email, :passwordHash, :name, NULL, 'ADMIN')
                ON CONFLICT (email)
                DO UPDATE SET
                    password_hash = EXCLUDED.password_hash,
                    name = EXCLUDED.name,
                    role = 'ADMIN'
                """)
                .setParameter("email", EMAIL)
                .setParameter("passwordHash", passwordHash)
                .setParameter("name", encryptedName)
                .executeUpdate();

        assertThat(changedRows).isEqualTo(1);
    }
}
