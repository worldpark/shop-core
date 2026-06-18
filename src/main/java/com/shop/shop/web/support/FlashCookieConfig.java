package com.shop.shop.web.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.FlashMapManager;

/**
 * Flash 메시지 저장소를 세션 → 쿠키로 전환하는 MVC 설정.
 *
 * <p>{@link org.springframework.web.servlet.DispatcherServlet}은 컨텍스트에서
 * <b>빈 이름 {@code flashMapManager}</b>(타입 {@link FlashMapManager})를 자동 탐색한다.
 * 이 이름으로 {@link CookieFlashMapManager}를 등록하면 기본 {@code SessionFlashMapManager}가
 * 대체되어, 컨트롤러의 {@code addFlashAttribute} 호출부를 전혀 수정하지 않아도
 * 저장 매체가 세션 → 쿠키로 바뀐다.
 *
 * <p><b>빈 이름 {@code flashMapManager} 정확성이 필수</b>: 이름이 다르면 기본
 * {@code SessionFlashMapManager}가 그대로 사용되어 조용히 무효화된다.
 */
@Configuration
public class FlashCookieConfig {

    /**
     * FLASH 쿠키 기반 FlashMapManager 등록.
     *
     * <p>빈 이름을 {@code flashMapManager}로 명시 — DispatcherServlet MVC 탐색 키.
     */
    @Bean("flashMapManager")
    public FlashMapManager flashMapManager(ObjectMapper objectMapper) {
        return new CookieFlashMapManager(objectMapper);
    }
}
