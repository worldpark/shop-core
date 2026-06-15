package com.shop.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class ShopCoreApplication {

    public static void main(String[] args) {
        // 시스템 전체를 KST로 운영: JVM 기본 타임존을 Asia/Seoul로 고정.
        // 로그 시각·LocalDate(Time).now()·Hibernate 타임존 처리가 KST 기준으로 일관된다.
        // (Instant는 절대시각이라 불변 — JSON KST 표현은 JacksonKstConfig가 담당)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(ShopCoreApplication.class, args);
    }

}
