package com.shop.shop.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletResponse;

/**
 * View(@Controller) 전용 예외 핸들러.
 * 뷰 이름: "error/error", 모델 키: status(int), message(String)
 * 마크업(templates/error/error.html)은 view-implementor 담당.
 *
 * Note: annotations=Controller.class는 @RestController(@Controller 메타)까지 이론상 포함하나,
 * @Order(LOWEST_PRECEDENCE)로 RestExceptionHandler(HIGHEST_PRECEDENCE)가 먼저 매칭되어
 * REST 요청은 여기까지 도달하지 않는다. 도메인 컨트롤러 등장 후 basePackages 한정 재조정 가능.
 */
@Slf4j
@ControllerAdvice(annotations = Controller.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public class ViewExceptionHandler {

    private static final String ERROR_VIEW = "error/error";

    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusinessException(BusinessException e, HttpServletResponse response) {
        log.warn("BusinessException (View): {}", e.getMessage());
        response.setStatus(e.getStatus().value());
        ModelAndView mav = new ModelAndView(ERROR_VIEW);
        mav.addObject("status", e.getStatus().value());
        mav.addObject("message", e.getMessage());
        return mav;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e, HttpServletResponse response) {
        log.warn("MaxUploadSizeExceededException (View): {}", e.getMessage());
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        ModelAndView mav = new ModelAndView(ERROR_VIEW);
        mav.addObject("status", HttpStatus.BAD_REQUEST.value());
        mav.addObject("message", "파일 크기가 허용 한도를 초과했습니다.");
        return mav;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleException(Exception e, HttpServletResponse response) {
        log.error("Unhandled exception (View)", e);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        ModelAndView mav = new ModelAndView(ERROR_VIEW);
        mav.addObject("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        mav.addObject("message", "서버 내부 오류가 발생했습니다.");
        return mav;
    }
}
