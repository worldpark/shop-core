package com.shop.shop.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * REST 응답용 경량 페이지 래퍼.
 *
 * <p>Spring Boot 3.3+ 에서 {@code Page}/{@code PageImpl}을 직접 직렬화하면
 * 불안정한 구조 경고가 발생하므로 명시적 record로 래핑한다.
 *
 * <p>spring-hateoas {@code PagedModel} 도입은 의존·표면 증가로 YAGNI — 이 record로 충분하다.
 *
 * @param <T> 콘텐츠 항목 타입
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    /**
     * Spring {@code Page<T>}를 {@code PageResponse<T>}로 변환.
     *
     * @param page Spring Page 객체
     * @param <T>  항목 타입
     * @return PageResponse 래퍼
     */
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
