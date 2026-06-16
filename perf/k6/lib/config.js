/**
 * lib/config.js
 *
 * 공통 설정 모듈 — BASE_URL, PROFILE 분기, thresholds, 시드 상수.
 * 모든 시나리오·프로파일은 이 파일을 import해 중복을 제거한다.
 *
 * 환경변수:
 *   BASE_URL                — 가압 대상 앱 루트 (기본: http://localhost:8080)
 *   PROFILE                 — 부하 프로파일 선택 smoke|load|stress (기본: smoke)
 *   ADMIN_EMAIL             — admin 계정 이메일 (기본: admin@example.com)
 *   ADMIN_PASSWORD          — admin 계정 비밀번호 (기본: Admin1234!)
 *   RUN_TAG                 — 런별 유니크 prefix (미지정 시 uuidv4 자동 생성)
 *   TOKEN_REFRESH_AFTER_SEC — 토큰 갱신 발화 임계(초). 기본 1500(25분). stress 런 중 갱신
 *                             기능 검증 시 5 등 짧게 지정. (auth.js getValidToken 참조)
 */

// ---------------------------------------------------------------
// 기본 연결 설정
// ---------------------------------------------------------------
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const PROFILE  = __ENV.PROFILE  || 'smoke';

// ---------------------------------------------------------------
// admin 자격증명 (k6 밖 1회 부트스트랩 후 고정)
// ---------------------------------------------------------------
export const ADMIN_EMAIL    = __ENV.ADMIN_EMAIL    || 'admin@example.com';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'Admin1234!';

// ---------------------------------------------------------------
// 시드 상수
// ---------------------------------------------------------------
export const SEED = {
  /** variant 재고 — VU×반복을 넉넉히 흡수(재고 고갈 409 배제). 베이스라인 측정 기간에는 변경 불필요. */
  VARIANT_STOCK: 1_000_000,
  /** 상품 기본 가격 (BigDecimal 직렬화 → 문자열 숫자) */
  BASE_PRICE: '10000',
  /** variant 가격 */
  VARIANT_PRICE: '10000',
  /** 상품명 prefix */
  PRODUCT_NAME_PREFIX: 'PERF-Product',
  /** SKU prefix */
  SKU_PREFIX: 'PERF-SKU',
  /** seller 이메일 도메인 */
  SELLER_EMAIL_DOMAIN: '@perf.local',
  /** buyer 이메일 도메인 */
  BUYER_EMAIL_DOMAIN: '@perf.local',
  /** 공통 테스트 비밀번호 (8자 이상, @Size(min=8) + @PasswordMatches 충족) */
  DEFAULT_PASSWORD: 'Perf1234!',
};

// ---------------------------------------------------------------
// 공통 비즈니스 불변식 thresholds (smoke/load 공유)
// — 에러율·5xx는 프로파일 불문 동일하게 적용
// ---------------------------------------------------------------
const BUSINESS_THRESHOLDS = {
  // HTTP 에러율 1% 미만 (4xx/5xx 포함)
  http_req_failed: ['rate<0.01'],

  // 주문 5xx — 락 붕괴 징후. 반드시 0이어야 함.
  order_5xx: ['count==0'],

  // order_conflict(409)은 임계로 죽이지 않음.
  // 낙관 충돌·재고 부족은 정상 비즈니스 흐름이므로 Counter로 가시화만.
};

// ---------------------------------------------------------------
// smoke thresholds
//
// 베이스라인: 2026-06-16 측정 (smoke, 5VU×30s, order-create.js).
//   관측: http_req_duration p95=40.21ms, p99=53.02ms, http_req_failed=0.00%,
//        order_5xx=0, order_created=3435 (~110 orders/s). 산출물: build/k6/order-create-smoke.json.
//   임계값은 절대 SLA가 아니라 "추세 회귀 감시" 출발점이다(로드맵 §9).
//   관측 p95/p99에 여유율 ×2~3(개발 머신 단일 런 변동·CI 하드웨어 편차 흡수)을 적용해
//   round 수치로 고정한다. 명백한 회귀(2~3배 지연)는 잡고, 노이즈 단발 실패는 피한다.
// ---------------------------------------------------------------
export const SMOKE_THRESHOLDS = {
  ...BUSINESS_THRESHOLDS,

  // 베이스라인(p95=40.21ms / p99=53.02ms)에 여유율 적용
  http_req_duration: [
    'p(95)<100',   // 베이스라인 p95=40.21ms × ~2.5 여유
    'p(99)<200',   // 베이스라인 p99=53.02ms × ~3.8 여유
  ],
};

// ---------------------------------------------------------------
// load thresholds
//
// 베이스라인: 2026-06-16 재측정 (깨끗한 DB, constant-arrival-rate, 60rps×1m).
//   ★ 목표 RPS를 100→60으로 낮춤(PROFILES.load 주석 참조). 100rps는 앱 처리 상한(≈90~100 orders/s)과
//     거의 같아 "포화점에서 측정"이었고, open 모델이 VU를 maxVUs까지 과투입(단일 variant 락 자기유발 경합)해
//     p95가 런마다 18~143ms로 flaky했다. 60rps는 상한·knee(stress 120rps) 아래라 VU가 ~5개로 안정
//     (에스컬레이션 없음). load=지속가능 운영수준 측정, 한계 탐색은 stress(003)가 담당.
//   확정 출처 = baselines/order-create-load.json: p95≈22ms, p99≈54ms, dropped=0, order_5xx=0 (vus max=5).
//   임계값 = p95(22ms) × ~2.5 → 60ms, p99(54ms) × ~2.5 → 150ms (round). smoke(40ms→100ms, ×2.5)와 일관.
// ---------------------------------------------------------------
export const LOAD_THRESHOLDS = {
  ...BUSINESS_THRESHOLDS,

  // load 베이스라인: 2026-06-16 재측정 (깨끗한 DB, 60rps×1m, baselines/order-create-load.json)
  // 관측 p95≈22ms × ~2.5 → 60ms, p99≈54ms × ~2.5 → 150ms (포화점 아래라 런별 변동 작음)
  http_req_duration: [
    'p(95)<60',    // 베이스라인 p95≈22ms × ~2.5 여유 → 60ms
    'p(99)<150',   // 베이스라인 p99≈54ms × ~2.5 여유 → 150ms
  ],

  // 목표 RPS 미달(under-provision) 감시 — dropped 과다면 maxVUs 상향 또는 목표 하향 필요.
  // 60rps(포화점 아래) 실측: dropped=0(0/s), VU 5개로 여유. 200rps+ under-provision은 71~138/s.
  // rate<3 → 정상 범위(0/s) 허용, 폭증 시 게이트 발동.
  dropped_iterations: ['rate<3'],
};

// ---------------------------------------------------------------
// stress thresholds — 진단용(느슨), 게이트 아님
//
// stress는 "어디서 무너지나"를 보는 것이 목적이므로
// p95/dropped_iterations 임계로 런을 죽이지 않는다(측정 대상이므로).
// 단, 과부하라도 비관적 락은 느려질 뿐 5xx로 무너지면 안 된다(order_5xx==0 유지).
// http_req_failed: 과부하 타임아웃이 실패로 잡힐 수 있어 위반 가능 → 곡선의 일부로 기록.
//
// 베이스라인: 2026-06-16 stress 실행 (ramping-arrival-rate 50→200rps)
//   002 탐색 근거: 100rps 정상(p95=18ms, dropped=0) / 200rps 붕괴(p95=634ms, dropped=71/s).
//   100~200rps 구간 정밀 점증으로 knee 식별.
// ---------------------------------------------------------------
export const STRESS_THRESHOLDS = {
  ...BUSINESS_THRESHOLDS,   // http_req_failed rate<0.01, order_5xx count==0
  // http_req_duration / dropped_iterations 임계 없음 — 붕괴 곡선 측정이 목적.
};

// ---------------------------------------------------------------
// 하위 호환: 기존 THRESHOLDS 심볼 유지 (smoke.js 가 참조)
// smoke.js 는 THRESHOLDS → SMOKE_THRESHOLDS 로 마이그레이션 권장
// ---------------------------------------------------------------
export const THRESHOLDS = SMOKE_THRESHOLDS;

// ---------------------------------------------------------------
// 프로파일별 VU/duration 설정
// profiles/smoke.js, profiles/load.js 가 import해서 사용한다.
//
// kind 필드:
//   'closed'       — vus + duration (기존 모델)
//   'arrival-rate' — constant-arrival-rate executor (open 모델)
// ---------------------------------------------------------------
export const PROFILES = {
  smoke: {
    kind: 'closed',
    vus: 5,
    duration: '30s',
    thresholds: SMOKE_THRESHOLDS,
  },
  load: {
    kind: 'arrival-rate',
    // 확정 목표: 60 rps × 1분 (포화점 아래 — 지속가능 운영수준).
    // §6.5 판정(2026-06-16) + flaky 완화(목표 100→60 하향):
    //   - 앱 처리 상한 ≈ 90~100 orders/s (단일 variant PESSIMISTIC_WRITE 직렬화).
    //   - 100rps(=상한 근처): open 모델이 VU를 maxVUs까지 과투입해 자기유발 경합 → p95가 런마다
    //     18~143ms로 flaky → "포화점 측정"이라 load 목표로 부적합.
    //   - 200rps: dropped=71/s, p95=634ms / 300rps: dropped=138/s, p95=886ms — 한계 초과(stress 영역).
    //   - 60rps(채택): VU ~5개로 안정(에스컬레이션 없음), p95≈22ms·p99≈54ms·dropped=0. SLO 내 지속가능.
    //   한계·붕괴점(knee≈120rps) 탐색은 stress(003)가 담당 — load와 역할 분리.
    rate: 60,
    timeUnit: '1s',
    duration: '1m',
    preAllocatedVUs: 15,
    maxVUs: 30,
    thresholds: LOAD_THRESHOLDS,
  },
  stress: {
    kind: 'ramping-arrival-rate',
    // 002 탐색 결과: 100rps 정상 / 200rps 붕괴. 100~200rps 구간을 정밀 점증해 knee 식별.
    // 단계 설계:
    //   [0→100]  30s — 워밍업·정상 구간 (002에서 confirmed SLO 충족)
    //   [100→120] 45s — 점증 시작
    //   [120→140] 45s
    //   [140→160] 45s
    //   [160→180] 45s
    //   [180→200] 45s — 붕괴 구간 진입 탐색
    //   [200→0]   15s — 쿨다운
    // 총 ~5분 (< access TTL 30분 → 토큰 갱신은 기능으로 넣되 이 런에선 미발화가 정상).
    // 토큰 갱신 기능 검증은 -e TOKEN_REFRESH_AFTER_SEC=5로 별도 smoke 런에서 수행.
    startRate: 50,
    timeUnit: '1s',
    stages: [
      { target: 100, duration: '30s' },
      { target: 120, duration: '45s' },
      { target: 140, duration: '45s' },
      { target: 160, duration: '45s' },
      { target: 180, duration: '45s' },
      { target: 200, duration: '45s' },
      { target: 0,   duration: '15s' },
    ],
    preAllocatedVUs: 50,
    maxVUs: 200,
    thresholds: STRESS_THRESHOLDS,
  },
};
