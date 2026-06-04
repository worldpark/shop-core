package com.shop.shop.common.exception.support;

import com.shop.shop.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/__test/view")
public class DummyViewController {

    @GetMapping("/business")
    public String business() {
        throw new BusinessException("뷰 비즈니스 오류", HttpStatus.BAD_REQUEST);
    }

    @GetMapping("/boom")
    public String boom() {
        throw new RuntimeException("뷰 예상치 못한 오류");
    }

    @GetMapping("/ok")
    public String ok() {
        return "dummy/ok";
    }
}
