package com.shop.shop.common.exception;

import com.shop.shop.common.exception.support.DummyRestController;
import com.shop.shop.common.exception.support.DummyViewController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * RestExceptionHandler 와 ViewExceptionHandler 가 동시에 존재하는 상황에서
 * 교차 매칭이 발생하지 않음을 검증한다.
 *
 * 핵심 함정: @RestController 는 @Controller 의 메타 애너테이션이므로
 * ViewExceptionHandler(@ControllerAdvice(annotations = Controller.class)) 가
 * RestController 요청까지 이론상 매칭될 수 있다.
 * 이 테스트는 두 advice 가 공존하는 환경에서 실제 교차 매칭이 없음을 단언한다.
 *
 * 분리 전략:
 *   - RestExceptionHandler: @RestControllerAdvice(annotations = RestController.class) + @Order(HIGHEST_PRECEDENCE)
 *   - ViewExceptionHandler: @ControllerAdvice(annotations = Controller.class) + @Order(LOWEST_PRECEDENCE)
 */
@WebMvcTest(
        controllers = {DummyRestController.class, DummyViewController.class},
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import({RestExceptionHandler.class, ViewExceptionHandler.class})
@TestPropertySource(properties = "spring.thymeleaf.check-template-location=false")
class AdviceSeparationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("REST 컨트롤러 예외 — ViewExceptionHandler(에러 뷰)가 아닌 RestExceptionHandler(JSON) 가 처리한다")
    void rest_endpoint_exception_is_handled_by_rest_advice_not_view_advice() throws Exception {
        mockMvc.perform(get("/__test/rest/business"))
                // RestExceptionHandler 가 처리 → HTTP 상태는 BusinessException.status(422)
                .andExpect(status().isUnprocessableEntity())
                // Content-Type 이 application/json 이어야 한다 (RestExceptionHandler 가 ResponseEntity 반환)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // JSON body 에 공통 ErrorResponse 필드 존재 → REST advice 개입 확인
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").exists())
                // ViewExceptionHandler 가 개입했다면 error/error 뷰가 렌더링되어 JSON 본문이 없어야 하지만,
                // 여기서는 JSON 본문이 존재하므로 View advice 미개입을 간접 확인한다.
                .andExpect(result -> {
                    String contentType = result.getResponse().getContentType();
                    assert contentType != null && contentType.contains("application/json")
                            : "REST 요청에 View advice 가 개입해 JSON 이 아닌 응답이 반환되었습니다: " + contentType;
                });
    }

    @Test
    @DisplayName("View 컨트롤러 예외 — RestExceptionHandler(JSON)가 아닌 ViewExceptionHandler(에러 뷰)가 처리한다")
    void view_endpoint_exception_is_handled_by_view_advice_not_rest_advice() throws Exception {
        mockMvc.perform(get("/__test/view/business"))
                // ViewExceptionHandler 가 처리 → HTTP 상태는 BusinessException.status(400)
                .andExpect(status().isBadRequest())
                // 뷰 이름이 error/error 이어야 한다 (ViewExceptionHandler 가 ModelAndView 반환)
                .andExpect(view().name("error/error"))
                // 모델에 status, message 존재 → View advice 개입 확인
                .andExpect(model().attributeExists("status", "message"))
                // RestExceptionHandler 가 개입했다면 application/json ContentType 이 반환되어야 하지만,
                // View advice 는 ModelAndView 를 반환하므로 JSON ContentType 이 아님을 확인한다.
                .andExpect(result -> {
                    String contentType = result.getResponse().getContentType();
                    assert contentType == null || !contentType.contains("application/json")
                            : "View 요청에 REST advice 가 개입해 JSON 응답이 반환되었습니다: " + contentType;
                });
    }
}
