package com.shop.shop.product.event;

import org.springframework.modulith.events.Externalized;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 상품 색인 변경 이벤트 — Transactional Outbox로 Kafka {@code product-search-index-changed} 토픽에 외부화된다.
 *
 * <p>발행 소유: product 모듈 (register/update/variant CRUD/재고 조정 진입점).
 * 발행 경로: {@code ApplicationEventPublisher.publishEvent(event)}를 {@code @Transactional} 안에서 호출
 * → Spring Modulith Event Publication Registry가 {@code event_publication} INCOMPLETE 저장(Outbox)
 * → 커밋 후 Kafka 외부화.
 *
 * <p>이벤트 계약 SSOT: docs/event-catalog.md(ProductSearchIndexChangedEvent).
 * 페이로드는 자족적 스냅샷 — indexer가 재조회 없이 페이로드만으로 ES upsert 가능.
 *
 * <p>구독자: shop-core product indexer({@code groupId=product-search-indexer}) 전용.
 * notification 비구독 — 알림 계약 아님(docs/architecture.md §5 색인 토픽 참조).
 *
 * <p>멱등 upsert 트리거: 변경 종류(생성/수정/상태전이/variant·재고 변화)를 구분하지 않고
 * "이 productId의 현재 색인 스냅샷이 이렇다"만 싣는다. ES {@code _id=productId},
 * external version={@code occurredAt} epoch millis로 순서 역전을 보호한다.
 *
 * <p>{@code displayPrice}·{@code purchasableVariantCount}는 공개 목록 집계
 * ({@code ProductRepository.findPublicProductsLatest})와 동일 식으로 산출하여
 * 검색-목록 일관성을 보장한다.
 *
 * @param eventId                이벤트 고유 식별자 (컨슈머 멱등 키)
 * @param occurredAt             이벤트 발생 시각 (ES external version 소스)
 * @param productId              색인 문서 _id
 * @param name                   상품명
 * @param description            상품 설명 (nullable)
 * @param categoryId             카테고리 ID (nullable — 미분류 허용)
 * @param categoryName           카테고리명 (nullable — 미분류 시 null)
 * @param status                 ProductStatus name (DRAFT/ON_SALE/SOLD_OUT/HIDDEN) — 색인 보존, 필터는 읽기
 * @param displayPrice           COALESCE(MIN 활성 variant price, basePrice) — 공개목록과 동일 식
 * @param purchasableVariantCount 활성 AND stock>0 개수 — 공개목록과 동일 식
 */
@Externalized("product-search-index-changed")
public record ProductSearchIndexChangedEvent(
        UUID eventId,
        Instant occurredAt,
        long productId,
        String name,
        String description,
        Long categoryId,
        String categoryName,
        String status,
        BigDecimal displayPrice,
        long purchasableVariantCount
) {

    /** 외부화 대상 Kafka 토픽명. KafkaTopicConfig 미러링 대상. */
    public static final String TOPIC = "product-search-index-changed";
}
