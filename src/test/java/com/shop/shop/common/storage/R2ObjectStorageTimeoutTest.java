package com.shop.shop.common.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2ObjectStorage 업로드 타임아웃 테스트.
 *
 * <p>plan §5.1 두 축 분리 검증:
 * <ul>
 *   <li>(a) 연결 타임아웃: 닫힌 포트 → {@link StorageException} 변환 확인</li>
 *   <li>(b) 응답 지연 타임아웃(핵심): ServerSocket accept 후 read 안 함 →
 *       {@link StorageException}이며 cause가 timeout 계열임을 단언.
 *       (a)는 (b)를 대체하지 못함: 연결 성공 + 무응답은 unroutable로 재현 불가</li>
 * </ul>
 *
 * <p>신규 외부 의존 없음 — {@link ServerSocket}만 사용.
 * S3Client는 {@link software.amazon.awssdk.core.client.config.ClientOverrideConfiguration}으로
 * apiCallTimeout/apiCallAttemptTimeout을 ms 단위로 짧게 설정해 테스트 속도를 보장한다.
 */
class R2ObjectStorageTimeoutTest {

    private static final String BUCKET = "shop-assets";
    private static final String DUMMY_ACCESS_KEY = "dummy-access";
    private static final String DUMMY_SECRET_KEY = "dummy-secret";

    // 테스트용 타임아웃 — 짧게 설정해 빠른 실패를 유도한다.
    // retry를 1회로 줄여 테스트 소요 시간을 최소화한다.
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofMillis(500);
    // 전체 타임아웃: attempt(500ms) × 1회 + 여유 200ms
    private static final Duration CALL_TIMEOUT = Duration.ofMillis(700);

    // (b) 테스트 자체 안전 타임아웃: put이 CALL_TIMEOUT 내 완료되지 않으면 테스트 자체가 행하는 것을 방지
    private static final long TEST_SAFETY_TIMEOUT_MS = 5_000L;

    private ServerSocket hangingServer;
    private ExecutorService acceptExecutor;

    @BeforeEach
    void setUp() {
        acceptExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-hang-accept");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        if (hangingServer != null && !hangingServer.isClosed()) {
            hangingServer.close();
        }
        if (acceptExecutor != null) {
            acceptExecutor.shutdownNow();
        }
    }

    /**
     * (a) 연결 타임아웃 — 닫힌 포트(refused)로 연결 시 {@link StorageException}으로 변환됨을 단언.
     *
     * <p>연결 거부(Connection refused)는 TCP RST로 즉시 실패한다.
     * 타임아웃이 짧더라도 연결 자체가 즉시 거부되므로 SDK 예외가 빠르게 발생하고,
     * {@link R2ObjectStorage}가 이를 {@link StorageException}으로 변환해야 한다.
     */
    @Test
    @DisplayName("(a) 연결 타임아웃 — 닫힌 포트에 연결 시 StorageException으로 변환됨")
    void put_connectionRefused_throwsStorageException() throws IOException {
        // 빈 포트 찾기: ServerSocket을 열었다가 즉시 닫으면 OS가 해당 포트를 CLOSE_WAIT 없이 해제
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        // probe가 닫혀 있으므로 이 포트는 연결 거부 상태

        R2ObjectStorage storage = buildStorage("http://127.0.0.1:" + closedPort);

        assertThatThrownBy(() ->
                storage.put("products/1", "test.jpg", "image/jpeg",
                        new ByteArrayInputStream("data".getBytes())))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("products/1");
    }

    /**
     * (b) 응답 지연 타임아웃(핵심) — 연결은 accept하되 read하지 않는 ServerSocket을 사용해
     * HTTP 요청 전송 후 응답 없는 상황을 재현한다.
     *
     * <p>이 테스트는 (a)로 대체할 수 없다: 연결 성공 + 무응답 상태는 unroutable 주소로 재현 불가.
     * 응답 지연 상황에서 {@link R2ObjectStorage}가 {@link StorageException}을 던지고,
     * cause가 {@link ApiCallTimeoutException} 또는 {@link ApiCallAttemptTimeoutException}임을 단언한다.
     */
    @Test
    @DisplayName("(b) 응답 지연 타임아웃 — 연결 후 무응답 서버에 put 시 StorageException(cause=timeout 계열)으로 변환됨")
    void put_hangingServer_throwsStorageExceptionWithTimeoutCause() throws IOException {
        // 연결은 accept하되 read하지 않는 경량 서버 — 별도 스레드에서 accept만 처리
        hangingServer = new ServerSocket(0);
        int hangingPort = hangingServer.getLocalPort();

        acceptExecutor.submit(() -> {
            try {
                while (!hangingServer.isClosed()) {
                    Socket socket = hangingServer.accept();  // accept만, read 안 함
                    // 소켓을 닫지 않고 유지 — 클라이언트 관점에서는 연결됐으나 응답이 없음
                    socket.setKeepAlive(true);
                }
            } catch (IOException ignored) {
                // ServerSocket이 닫히면 accept에서 예외 발생 — 정상 종료
            }
        });

        R2ObjectStorage storage = buildStorage("http://127.0.0.1:" + hangingPort);

        // 테스트 자체 안전 타임아웃: TEST_SAFETY_TIMEOUT_MS 내 완료 안 되면 테스트 행으로 판정
        long startMs = System.currentTimeMillis();

        StorageException thrown = null;
        try {
            storage.put("products/1", "test.jpg", "image/jpeg",
                    new ByteArrayInputStream("data".getBytes()));
        } catch (StorageException e) {
            thrown = e;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        assertThat(elapsedMs)
                .as("put이 TEST_SAFETY_TIMEOUT_MS(%dms) 내에 완료되어야 함(현재 %dms) — 무한 매달림 없음 확인",
                        TEST_SAFETY_TIMEOUT_MS, elapsedMs)
                .isLessThan(TEST_SAFETY_TIMEOUT_MS);

        // 단언 1: StorageException 인스턴스여야 한다
        assertThat(thrown)
                .as("put이 StorageException으로 변환되어야 함")
                .isNotNull()
                .isInstanceOf(StorageException.class);

        // 단언 2: cause가 timeout 계열(ApiCallTimeoutException 또는 ApiCallAttemptTimeoutException)이어야 한다.
        // §1.3 예외 변환 확정 작업의 RED 가드 — catch(SdkException)가 없으면 raw SDK 예외가 전파되어 이 단언이 실패한다.
        Throwable cause = thrown.getCause();
        assertThat(cause)
                .as("StorageException의 cause가 ApiCallTimeoutException 또는 ApiCallAttemptTimeoutException이어야 함")
                .isNotNull();
        boolean isTimeoutCause = (cause instanceof ApiCallTimeoutException)
                || (cause instanceof ApiCallAttemptTimeoutException);
        // cause 체인에서 timeout 계열을 찾는다 (SDK가 wrap할 수 있으므로)
        if (!isTimeoutCause) {
            Throwable chain = cause.getCause();
            while (chain != null) {
                if ((chain instanceof ApiCallTimeoutException)
                        || (chain instanceof ApiCallAttemptTimeoutException)) {
                    isTimeoutCause = true;
                    break;
                }
                chain = chain.getCause();
            }
        }
        assertThat(isTimeoutCause)
                .as("cause 체인에 ApiCallTimeoutException 또는 ApiCallAttemptTimeoutException이 있어야 함. 실제 cause: %s",
                        cause)
                .isTrue();
    }

    /**
     * 테스트용 R2ObjectStorage를 직접 new로 구성한다 (044 MinIO 통합 테스트 패턴 재사용).
     *
     * <p>apiCallAttemptTimeout/apiCallTimeout을 짧게 + retryPolicy 최대 시도 1회로 설정해
     * 테스트 소요 시간을 최소화한다.
     */
    private R2ObjectStorage buildStorage(String endpoint) {
        ClientOverrideConfiguration overrideConfig = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                .apiCallTimeout(CALL_TIMEOUT)
                .build();

        S3Client s3Client = S3Client.builder()
                .endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(DUMMY_ACCESS_KEY, DUMMY_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .overrideConfiguration(overrideConfig)
                .build();

        StorageProperties props = new StorageProperties();
        props.setAllowedExtensions(List.of("jpg", "jpeg", "png", "gif", "webp"));

        StorageProperties.R2 r2 = new StorageProperties.R2();
        r2.setEndpoint(endpoint);
        r2.setBucket(BUCKET);
        r2.setRegion("us-east-1");
        r2.setAccessKey(DUMMY_ACCESS_KEY);
        r2.setSecretKey(DUMMY_SECRET_KEY);
        r2.setApiCallAttemptTimeoutMs(ATTEMPT_TIMEOUT.toMillis());
        r2.setApiCallTimeoutMs(CALL_TIMEOUT.toMillis());
        r2.setCacheControl("public, max-age=31536000, immutable");
        props.setR2(r2);

        return new R2ObjectStorage(s3Client, props);
    }
}
