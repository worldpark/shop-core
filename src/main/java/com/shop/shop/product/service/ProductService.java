package com.shop.shop.product.service;

import com.shop.shop.common.exception.CategoryNotFoundException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.ProductSearchSnapshotProjection;
import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.shop.shop.common.exception.BusinessException;

/**
 * 상품 도메인 서비스.
 *
 * <p>상품 등록, 수정, 조회 도메인 로직을 단일 소유한다.
 * category 존재 검증, 소유권 검사(타인 상품 404) 불변식을 여기서 담당한다.
 *
 * <p><b>순수 도메인 — member/UserDirectory 포트 비의존.</b>
 * actorId(long)/actorIsAdmin(boolean)을 인자로만 받으며,
 * principal→userId 변환은 진입점(ServiceResponse/ViewController)의 책임이다.
 *
 * <p>레이어: *Controller → ProductServiceResponse(REST) / ViewController(View) → ProductService → *Repository
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 상품 등록.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>basePrice 음수 방어(Bean Validation이 1차, 서비스가 2차)</li>
     *   <li>categoryId가 있으면 카테고리 존재 검증</li>
     *   <li>Product.create(status=DRAFT 강제)</li>
     *   <li>save 후 색인 이벤트 발행(IDENTITY 전략으로 save 후 id 항상 세팅)</li>
     * </ol>
     *
     * @param ownerId     소유자 userId (long)
     * @param categoryId  카테고리 ID (null = 미분류)
     * @param name        상품명
     * @param description 상품 설명 (null 허용)
     * @param basePrice   기본 가격 (≥ 0)
     * @return 저장된 Product Entity (status=DRAFT)
     * @throws BusinessException         basePrice 음수(400)
     * @throws CategoryNotFoundException categoryId 존재 안함(404)
     */
    public Product register(long ownerId, Long categoryId, String name,
                             String description, BigDecimal basePrice) {
        validateBasePrice(basePrice);
        Category category = resolveCategory(categoryId);
        Product product = Product.create(ownerId, category, name, description, basePrice);
        // save가 반환한 영속 엔티티(IDENTITY로 id 채워짐)를 사용한다 — 색인 이벤트가 id를 참조하므로.
        Product saved = productRepository.save(product);
        publishSearchIndexEvent(saved.getId());
        return saved;
    }

    /**
     * 상품 수정.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>대상 존재 확인 → {@link ProductNotFoundException}(404)</li>
     *   <li>소유권 검사(§1.4): !actorIsAdmin && ownerId != actorId → {@link ProductAccessDeniedException}(404)</li>
     *   <li>categoryId 검증(있으면)</li>
     *   <li>basePrice 음수 방어</li>
     *   <li>product.update(...) dirty checking</li>
     * </ol>
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자가 ADMIN인지 여부 (true면 소유권 검사 스킵)
     * @param productId    수정할 상품 ID
     * @param categoryId   수정할 카테고리 ID (null = 미분류)
     * @param name         수정할 상품명
     * @param description  수정할 상품 설명 (null 허용)
     * @param basePrice    수정할 기본 가격 (≥ 0)
     * @param status       수정할 상태
     * @return 수정된 Product Entity
     * @throws ProductNotFoundException      상품 미존재(404)
     * @throws ProductAccessDeniedException  타인 상품 접근(404)
     * @throws CategoryNotFoundException     category 미존재(404)
     * @throws BusinessException             basePrice 음수(400)
     */
    public Product update(long actorId, boolean actorIsAdmin, long productId,
                           Long categoryId, String name, String description,
                           BigDecimal basePrice, ProductStatus status) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        checkOwnership(actorId, actorIsAdmin, product, productId);
        validateBasePrice(basePrice);
        Category category = resolveCategory(categoryId);
        product.update(category, name, description, basePrice, status);
        publishSearchIndexEvent(productId);
        return product;
    }

    /**
     * 상품 단건 조회 (수정 화면용).
     * 소유권 검사 포함 — 타인 상품 edit 화면 차단.
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자가 ADMIN인지 여부
     * @param productId    조회할 상품 ID
     * @return 조회된 Product Entity
     * @throws ProductNotFoundException     상품 미존재(404)
     * @throws ProductAccessDeniedException 타인 상품 접근(404)
     */
    @Transactional(readOnly = true)
    public Product getForEdit(long actorId, boolean actorIsAdmin, long productId) {
        return getOwnedProduct(actorId, actorIsAdmin, productId);
    }

    /**
     * 판매자 본인 상품 목록 조회.
     *
     * <p>ownerId 한정 페이지 조회 — 소유 필터는 쿼리가 구조적으로 보장한다.
     * 빈 결과는 정상(예외 없음). 상태 무관 전체(DRAFT/HIDDEN 포함).
     *
     * <p><b>ADMIN 특례 없음</b> — 목록은 항상 본인 ownerId. IDOR 방지 단순화.
     *
     * @param ownerId  소유자 userId (본인 한정)
     * @param pageable 페이지 정보
     * @return 소유자 상품 Page (Entity — facade 경계에서 DTO 변환)
     */
    @Transactional(readOnly = true)
    public Page<Product> getMyProducts(long ownerId, Pageable pageable) {
        return productRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId, pageable);
    }

    /**
     * 상품 로드 + 소유권 검사 (단일 출처 메서드).
     *
     * <p>옵션/variant 서비스 등 "상품 로드 + 소유권 검사"를 필요로 하는 모든 곳에서 이 메서드를 호출한다.
     * 소유권 불변식(V12/V13)이 한 곳에 유지된다.
     *
     * @param actorId      행위자 userId
     * @param actorIsAdmin 행위자가 ADMIN인지 여부 (true면 소유권 검사 스킵)
     * @param productId    조회할 상품 ID
     * @return 조회된 Product Entity (소유권 검사 통과)
     * @throws ProductNotFoundException     상품 미존재(404) — V13
     * @throws ProductAccessDeniedException 타인 상품 접근(404) — V12
     */
    @Transactional(readOnly = true)
    public Product getOwnedProduct(long actorId, boolean actorIsAdmin, long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        checkOwnership(actorId, actorIsAdmin, product, productId);
        return product;
    }

    /**
     * 소유권 검사.
     * ADMIN이면 스킵. 소유자가 아니면 404(ProductAccessDeniedException — 존재 은닉).
     */
    private void checkOwnership(long actorId, boolean actorIsAdmin, Product product, long productId) {
        if (!actorIsAdmin && !product.getOwnerId().equals(actorId)) {
            throw new ProductAccessDeniedException(productId);
        }
    }

    /**
     * basePrice 음수 방어 (Bean Validation 이후 서비스 2차 방어).
     */
    private void validateBasePrice(BigDecimal basePrice) {
        if (basePrice != null && basePrice.signum() < 0) {
            throw new BusinessException("기본 가격은 0 이상이어야 합니다.", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * categoryId로 Category 조회 (null이면 null 반환 — 미분류).
     */
    private Category resolveCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
    }

    /**
     * 색인 스냅샷을 조회해 {@link ProductSearchIndexChangedEvent}를 Outbox에 발행한다.
     *
     * <p>같은 {@code @Transactional} 내에서 호출하므로 Hibernate auto-flush로 직전 변경이
     * flush된 후 스냅샷 쿼리가 실행되어 최신 상태를 반영한다(결정 3 — 재고 일관성).
     * ES 직접 쓰기 없음 — dual-write 금지(ADR-011). Outbox({@code event_publication})에 적재 후
     * 커밋 시 Kafka로 외부화된다.
     *
     * <p>상품 미존재 시(예: 등록 후 즉시 롤백되는 경우) 스냅샷 조회가 empty를 반환하므로
     * 이벤트를 발행하지 않는다.
     *
     * @param productId 색인할 상품 ID
     */
    public void publishSearchIndexEvent(long productId) {
        productRepository.findSearchSnapshot(productId).ifPresent(snapshot -> {
            ProductSearchIndexChangedEvent event = new ProductSearchIndexChangedEvent(
                    UUID.randomUUID(),
                    Instant.now(),
                    snapshot.productId(),
                    snapshot.name(),
                    snapshot.description(),
                    snapshot.categoryId(),
                    snapshot.categoryName(),
                    snapshot.status().name(),
                    snapshot.displayPrice(),
                    snapshot.purchasableVariantCount()
            );
            eventPublisher.publishEvent(event);
        });
    }
}
