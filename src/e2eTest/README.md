# E2E 테스트 (Playwright for Java)

브라우저 기반 종단(End-to-End) 테스트. 회원가입→로그인→인증 홈 같은 **핵심 사용자 여정**을
실제 Chromium으로 검증한다. Thymeleaf SSR + Spring Security(폼 로그인/CSRF/세션) 통합 경로를
단위·슬라이스 테스트로는 잡을 수 없는 수준에서 확인한다.

> 기존 Node.js Playwright(`/e2e`)를 Playwright for Java로 이관했다.
> 엔진·로케이터·웹-퍼스트 assertion은 동일하며, 러너만 JUnit5 + Gradle로 통일했다.

## 구성

- 소스셋: `src/e2eTest/java` (일반 `test`와 분리 — `check`/`test`에 포함되지 않음)
- 의존성: `com.microsoft.playwright:playwright`
- 산출물: 실패 시 스크린샷·trace가 `build/e2e-artifacts/`에 저장됨 (gitignore)

## 사전 준비

1. **브라우저 설치 (최초 1회)**
   ```bash
   ./gradlew installPlaywrightBrowsers
   ```
   Playwright Java는 JS판과 별개의 Chromium 빌드를 쓰므로 한 번 설치해야 한다.

2. **인프라 + 앱 기동** — E2E는 실행 중인 앱에 접속한다(앱을 자동 기동하지 않음).
   ```bash
   # 인프라 (repo 루트)
   docker compose -f docker/shop/docker-compose.yml up -d
   # 앱 (shop-core) — 필수 환경변수 설정 후
   SHOP_SECURITY_JWT_SECRET=... ./gradlew bootRun
   ```

## 실행

```bash
./gradlew e2eTest
```

- 대상 URL은 `SHOP_CORE_BASE_URL` 환경변수로 변경 (기본 `http://localhost:8080`).
- 실패 시 `build/e2e-artifacts/<클래스>_<메서드>.png` 및 `-trace.zip` 확인.
  trace는 `npx playwright show-trace <파일>` 또는 https://trace.playwright.dev 로 연다.

## 주의 / 관례

- **`Pattern.quote()`(→ `\Q..\E`) 금지.** Playwright는 Java `Pattern`을 내부 JS 정규식으로
  변환하는데 `\Q..\E`는 JS 정규식이 지원하지 않아 매치가 깨진다. URL 등은 정확 문자열
  매칭(`hasURL("...")`)이나 JS 호환 정규식(`Pattern.compile("/login\\?signup")`)을 사용한다.
- `Page`/`BrowserContext`는 스레드 안전하지 않다. 병렬 실행 시 테스트(스레드)마다 별도
  컨텍스트를 생성한다(현재 `@BeforeEach`에서 컨텍스트 생성).
- E2E는 느리고 외부 의존이 크므로 **핵심 여정 위주로 얇게** 유지한다. 폼 검증·권한 분기 등
  세부는 MockMvc/슬라이스 테스트로 커버한다.
