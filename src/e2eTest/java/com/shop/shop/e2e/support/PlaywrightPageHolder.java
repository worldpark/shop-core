package com.shop.shop.e2e.support;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

/**
 * E2E 테스트가 현재 {@link Page}/{@link BrowserContext}를 노출하는 계약.
 *
 * <p>{@link PlaywrightArtifactsExtension}이 실패 시 스크린샷·trace를 캡처하기 위해
 * 테스트 인스턴스로부터 활성 page/context에 접근하는 통로다.
 */
public interface PlaywrightPageHolder {

    /** 현재 테스트의 활성 Page (없으면 null). */
    Page page();

    /** 현재 테스트의 활성 BrowserContext (없으면 null). */
    BrowserContext context();
}
