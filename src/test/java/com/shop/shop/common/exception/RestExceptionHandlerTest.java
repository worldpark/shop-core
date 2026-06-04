package com.shop.shop.common.exception;

import com.shop.shop.common.exception.support.DummyRestController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = DummyRestController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import(RestExceptionHandler.class)
class RestExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("BusinessException 발생 시 지정 상태코드와 공통 JSON 포맷 반환")
    void business_exception_returns_custom_status_with_json_body() throws Exception {
        mockMvc.perform(get("/__test/rest/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("비즈니스 오류 발생"))
                .andExpect(jsonPath("$.path").value("/__test/rest/business"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("미처리 RuntimeException 발생 시 500 fallback JSON 반환")
    void unhandled_exception_returns_500_json() throws Exception {
        mockMvc.perform(get("/__test/rest/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/__test/rest/boom"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("@Valid 검증 실패 시 400 + 검증 메시지 반환")
    void validation_failure_returns_400_with_messages() throws Exception {
        mockMvc.perform(post("/__test/rest/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
