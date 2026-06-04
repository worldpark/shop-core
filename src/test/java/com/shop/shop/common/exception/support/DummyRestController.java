package com.shop.shop.common.exception.support;

import com.shop.shop.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/__test/rest")
public class DummyRestController {

    @GetMapping("/business")
    public String business() {
        throw new BusinessException("비즈니스 오류 발생", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @GetMapping("/boom")
    public String boom() {
        throw new RuntimeException("예상치 못한 오류");
    }

    @PostMapping("/valid")
    public String valid(@Valid @RequestBody ValidRequest request) {
        return "ok";
    }

    @Getter
    public static class ValidRequest {
        @NotNull(message = "name은 필수입니다.")
        @NotBlank(message = "name은 공백일 수 없습니다.")
        private String name;

        @NotNull(message = "email은 필수입니다.")
        private String email;
    }
}
