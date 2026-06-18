package com.shop.shop.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * PII 컬럼 봉투암호화 JPA AttributeConverter.
 *
 * <p>평문 String ↔ DB 암호문 String 변환. 저장: encrypt, 조회: decrypt.
 * null은 null 통과(nullable 컬럼 대응).
 *
 * <p>암호문 형식: {@code v1:iv:wrappedDek:cipher} (비결정성 봉투암호화).
 * 대상 컬럼은 검색/유니크 미사용 필드에 한함(비결정성으로 인해 검색 불가).
 *
 * <p>Spring Boot가 JPA 컨버터에 빈 주입을 지원한다(hibernate.resource.beans.container 기본 활성).
 * @Component + @Converter를 함께 선언해 Spring 빈으로 EnvelopeEncryptionService를 주입받는다.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final EnvelopeEncryptionService crypto;

    public EncryptedStringConverter(EnvelopeEncryptionService crypto) {
        this.crypto = crypto;
    }

    /**
     * 엔티티 평문 → DB 암호문.
     *
     * @param plain 평문 (null 허용)
     * @return 암호문 또는 null
     */
    @Override
    public String convertToDatabaseColumn(String plain) {
        return plain == null ? null : crypto.encrypt(plain);
    }

    /**
     * DB 암호문 → 엔티티 평문.
     *
     * @param enc 암호문 (null 허용)
     * @return 평문 또는 null
     */
    @Override
    public String convertToEntityAttribute(String enc) {
        return enc == null ? null : crypto.decrypt(enc);
    }
}
