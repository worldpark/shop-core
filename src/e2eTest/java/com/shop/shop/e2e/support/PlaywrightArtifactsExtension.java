package com.shop.shop.e2e.support;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 테스트 실패 시 스크린샷과 Playwright trace를 {@code build/e2e-artifacts/}에 저장하는 JUnit 확장.
 *
 * <p>JS Playwright 러너의 {@code screenshot: only-on-failure} / {@code trace} 자동 캡처를
 * Java(JUnit5)에서 대체한다. {@link AfterTestExecutionCallback}은 {@code @AfterEach}(컨텍스트 close)
 * 직전에 실행되므로, page/context가 아직 살아 있는 시점에 산출물을 확보할 수 있다.
 *
 * <p>성공한 테스트는 trace를 저장하지 않는다(파일 폭증 방지). 추적은 테스트가
 * {@code context.tracing().start(...)}로 시작했다는 전제에서 동작한다.
 */
public class PlaywrightArtifactsExtension implements AfterTestExecutionCallback {

    private static final Path ARTIFACT_DIR = Path.of("build", "e2e-artifacts");

    @Override
    public void afterTestExecution(ExtensionContext context) {
        if (context.getExecutionException().isEmpty()) {
            return; // 성공 — 산출물 보존 안 함
        }
        if (!(context.getRequiredTestInstance() instanceof PlaywrightPageHolder holder)) {
            return;
        }
        Page page = holder.page();
        if (page == null) {
            return;
        }

        String baseName = context.getRequiredTestClass().getSimpleName()
                + "_" + context.getRequiredTestMethod().getName();
        try {
            Files.createDirectories(ARTIFACT_DIR);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(ARTIFACT_DIR.resolve(baseName + ".png"))
                    .setFullPage(true));
            if (holder.context() != null) {
                holder.context().tracing().stop(new Tracing.StopOptions()
                        .setPath(ARTIFACT_DIR.resolve(baseName + "-trace.zip")));
            }
        } catch (IOException e) {
            // 산출물 저장 실패는 테스트 결과에 영향을 주지 않는다.
            System.err.println("[E2E] 실패 산출물 저장 중 오류: " + e.getMessage());
        }
    }
}
