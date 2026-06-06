package com.shop.shop.web.product;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.product.dto.ProductImageManagementView;
import com.shop.shop.product.spi.SellerProductImageFacade;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;

/**
 * SELLER 상품 이미지 관리 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>principal 통일(View): form login session principal = UserDetails(username=email).
 * {@link CurrentActorResolver}가 {@code auth.getName()}과 ROLE_ADMIN 직접 보유 여부를 추출한다.
 * facade 내부에서 {@code UserDirectory.findUserIdByEmail}로 actorId를 획득한다.
 *
 * <p>레이어: SellerProductImageViewController → {@link SellerProductImageFacade}(published port)
 * → ProductImageService → Repository.
 * 모델엔 DTO/ViewModel·폼 객체만 담는다 (Entity·enum 금지).
 *
 * <p>모델 키 계약 (Backend-View Contract 준수):
 * <ul>
 *   <li>{@code product} — {@code SellerProductRef}</li>
 *   <li>{@code images} — {@code List&lt;ProductImageResponse&gt;}</li>
 *   <li>{@code imageUploadForm} — {@link ImageUploadForm}</li>
 * </ul>
 * View name: {@code seller/product-images}
 * 성공/실패 redirect: {@code redirect:/seller/products/{productId}/images}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 */
@Slf4j
@Controller
@RequestMapping("/seller/products/{productId}/images")
@RequiredArgsConstructor
public class SellerProductImageViewController {

    private static final String PRODUCT_IMAGES_VIEW = "seller/product-images";

    private final SellerProductImageFacade sellerProductImageFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 이미지 관리 화면.
     * GET /seller/products/{productId}/images
     *
     * <p>facade.getManagementView → product/images 모델 키 분해 + 빈 업로드 폼 추가.
     *
     * @param productId 대상 상품 ID
     * @param auth      SecurityContext 인증 객체
     * @param model     Spring MVC 모델
     * @return view name "seller/product-images"
     */
    @GetMapping
    public String managementView(
            @PathVariable long productId,
            Authentication auth,
            Model model) {

        populateManagementModel(model, productId, auth);
        model.addAttribute("imageUploadForm", new ImageUploadForm());
        return PRODUCT_IMAGES_VIEW;
    }

    /**
     * 이미지 업로드 처리.
     * POST /seller/products/{productId}/images
     *
     * <p>Bean검증 실패(파일 미선택) → flashError + redirect.
     * 성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * <p>MultipartFile에서 originalFilename/contentType/inputStream을 꺼내 facade에 전달.
     * InputStream은 try-with-resources로 열어 facade 호출을 그 안에서 수행한다.
     *
     * @param productId     대상 상품 ID
     * @param form          업로드 폼 (file 필드)
     * @param bindingResult 검증 결과
     * @param auth          SecurityContext 인증 객체
     * @param ra            RedirectAttributes
     * @return redirect
     */
    @PostMapping
    public String upload(
            @PathVariable long productId,
            @Valid @ModelAttribute("imageUploadForm") ImageUploadForm form,
            BindingResult bindingResult,
            Authentication auth,
            RedirectAttributes ra) {

        String redirectUrl = "redirect:/seller/products/" + productId + "/images";

        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("flashError", "업로드할 파일을 선택해주세요.");
            return redirectUrl;
        }

        // 빈 파일(파일 미선택) 명시적 검증 — @NotNull은 빈 MultipartFile에 발동하지 않아 별도 처리
        if (form.getFile() == null || form.getFile().isEmpty()) {
            ra.addFlashAttribute("flashError", "업로드할 파일을 선택해주세요.");
            return redirectUrl;
        }

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            String originalFilename = form.getFile().getOriginalFilename();
            String contentType = form.getFile().getContentType();

            try (InputStream inputStream = form.getFile().getInputStream()) {
                sellerProductImageFacade.upload(
                        actor.email(), actor.admin(), productId,
                        originalFilename, contentType, inputStream);
            }

            ra.addFlashAttribute("flashSuccess", "이미지가 업로드되었습니다.");
        } catch (BusinessException e) {
            log.warn("이미지 업로드 실패: actorEmail={}, productId={}, reason={}",
                    auth.getName(), productId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        } catch (IOException e) {
            log.error("이미지 스트림 읽기 실패: actorEmail={}, productId={}", auth.getName(), productId, e);
            ra.addFlashAttribute("flashError", "파일 읽기 중 오류가 발생했습니다.");
        }

        return redirectUrl;
    }

    /**
     * 대표 이미지 지정 처리.
     * POST /seller/products/{productId}/images/{imageId}/primary
     *
     * <p>성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * @param productId 대상 상품 ID
     * @param imageId   대표로 지정할 이미지 ID
     * @param auth      SecurityContext 인증 객체
     * @param ra        RedirectAttributes
     * @return redirect
     */
    @PostMapping("/{imageId}/primary")
    public String setPrimary(
            @PathVariable long productId,
            @PathVariable long imageId,
            Authentication auth,
            RedirectAttributes ra) {

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            sellerProductImageFacade.setPrimary(actor.email(), actor.admin(), productId, imageId);
            ra.addFlashAttribute("flashSuccess", "대표 이미지가 변경되었습니다.");
        } catch (BusinessException e) {
            log.warn("대표 이미지 지정 실패: actorEmail={}, productId={}, imageId={}, reason={}",
                    auth.getName(), productId, imageId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/products/" + productId + "/images";
    }

    /**
     * 이미지 정렬 순서 변경 처리.
     * POST /seller/products/{productId}/images/{imageId}/order
     *
     * <p>성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * @param productId 대상 상품 ID
     * @param imageId   변경할 이미지 ID
     * @param sortOrder 새 정렬 순서 (폼 필드명: sortOrder — Backend-View Contract 준수)
     * @param auth      SecurityContext 인증 객체
     * @param ra        RedirectAttributes
     * @return redirect
     */
    @PostMapping("/{imageId}/order")
    public String changeOrder(
            @PathVariable long productId,
            @PathVariable long imageId,
            @RequestParam int sortOrder,
            Authentication auth,
            RedirectAttributes ra) {

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            sellerProductImageFacade.changeOrder(actor.email(), actor.admin(), productId, imageId, sortOrder);
            ra.addFlashAttribute("flashSuccess", "정렬 순서가 변경되었습니다.");
        } catch (BusinessException e) {
            log.warn("정렬 순서 변경 실패: actorEmail={}, productId={}, imageId={}, sortOrder={}, reason={}",
                    auth.getName(), productId, imageId, sortOrder, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/products/" + productId + "/images";
    }

    /**
     * 이미지 삭제 처리.
     * POST /seller/products/{productId}/images/{imageId}/delete
     *
     * <p>성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * @param productId 대상 상품 ID
     * @param imageId   삭제할 이미지 ID
     * @param auth      SecurityContext 인증 객체
     * @param ra        RedirectAttributes
     * @return redirect
     */
    @PostMapping("/{imageId}/delete")
    public String delete(
            @PathVariable long productId,
            @PathVariable long imageId,
            Authentication auth,
            RedirectAttributes ra) {

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            sellerProductImageFacade.delete(actor.email(), actor.admin(), productId, imageId);
            ra.addFlashAttribute("flashSuccess", "이미지가 삭제되었습니다.");
        } catch (BusinessException e) {
            log.warn("이미지 삭제 실패: actorEmail={}, productId={}, imageId={}, reason={}",
                    auth.getName(), productId, imageId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/products/" + productId + "/images";
    }

    /**
     * 관리 화면 공통 모델 데이터 주입.
     * product / images 키를 facade.getManagementView 결과에서 분해해 주입한다.
     */
    private void populateManagementModel(Model model, long productId, Authentication auth) {
        CurrentActor actor = currentActorResolver.resolve(auth);
        ProductImageManagementView view = sellerProductImageFacade.getManagementView(
                actor.email(), actor.admin(), productId);
        model.addAttribute("product", view.product());
        model.addAttribute("images", view.images());
    }
}
