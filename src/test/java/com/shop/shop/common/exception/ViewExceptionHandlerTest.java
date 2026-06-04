package com.shop.shop.common.exception;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(
        controllers = DummyViewController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(ViewExceptionHandler.class)
@TestPropertySource(properties = "spring.thymeleaf.check-template-location=false")
class ViewExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("BusinessException 발생 시 error/error 뷰 이름과 status/message 모델 반환")
    void business_exception_returns_error_view_with_model() throws Exception {
        mockMvc.perform(get("/__test/view/business"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error/error"))
                .andExpect(model().attributeExists("status", "message"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("미처리 RuntimeException 발생 시 500 에러 뷰 반환")
    void unhandled_exception_returns_500_error_view() throws Exception {
        mockMvc.perform(get("/__test/view/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error/error"))
                .andExpect(model().attributeExists("status", "message"));
    }

    @Test
    @DisplayName("View 에러 응답이 JSON이 아님을 확인")
    void view_error_response_is_not_json() throws Exception {
        mockMvc.perform(get("/__test/view/business"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    String contentType = result.getResponse().getContentType();
                    // JSON Content-Type이 아님을 검증
                    if (contentType != null) {
                        assert !contentType.contains("application/json")
                                : "View 에러 응답이 JSON이어서는 안 됩니다: " + contentType;
                    }
                });
    }
}
