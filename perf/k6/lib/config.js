/**
 * lib/config.js
 *
 * 공통 설정 모듈 — BASE_URL, PROFILE 분기, thresholds, 시드 상수.
 * 모든 시나리오·프로파일은 이 파일을 import해 중복을 제거한다.
 *
 * 환경변수:
 *   BASE_URL       — 가압 대상 앱 루트 (기본: http://localhost:8080)
 *   PROFILE        — 부하 프로파일 선택 smoke|load|stress (기본: smoke)
 *   ADMIN_EMAIL    — admin 계정 이메일 (기본: admin@example.com)
 *   ADMIN_PASSWORD — admin 계정 비밀번호 (기본: Admin1234!)
 *   RUN_TAG        — 런별 유니크 prefix (미지정 시 uuidv4 자동 생성)
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
// thresholds — lib/config.js에서 형태 정의, 프로파일이 import해 사용
//
// 베이스라인: 2026-06-16 측정 (smoke, 5VU×30s, order-create.js).
//   관측: http_req_duration p95=40.21ms, p99=53.02ms, http_req_failed=0.00%,
//        order_5xx=0, order_created=3435 (~110 orders/s). 산출물: build/k6/order-create-smoke.json.
//   임계값은 절대 SLA가 아니라 "추세 회귀 감시" 출발점이다(로드맵 §9).
//   관측 p95/p99에 여유율 ×2~3(개발 머신 단일 런 변동·CI 하드웨어 편차 흡수)을 적용해
//   round 수치로 고정한다. 명백한 회귀(2~3배 지연)는 잡고, 노이즈 단발 실패는 피한다.
// ---------------------------------------------------------------
export const THRESHOLDS = {
  // HTTP 에러율 1% 미만 (4xx/5xx 포함)
  http_req_failed: ['rate<0.01'],

  // HTTP 응답 시간 — 베이스라인(p95=40.21ms / p99=53.02ms)에 여유율 적용
  http_req_duration: [
    'p(95)<100',   // 베이스라인 p95=40.21ms × ~2.5 여유
    'p(99)<200',   // 베이스라인 p99=53.02ms × ~3.8 여유
  ],

  // 주문 5xx — 락 붕괴 징후. 베이스라인에서 반드시 0이어야 함.
  order_5xx: ['count==0'],

  // order_conflict(409)은 임계로 죽이지 않음.
  // 낙관 충돌/재고 부족은 정상 비즈니스 흐름이므로 Counter로 가시화만.
};

// ---------------------------------------------------------------
// 프로파일별 VU/duration 설정
// profiles/smoke.js 가 import해서 사용 가능하도록 export
// ---------------------------------------------------------------
export const PROFILES = {
  smoke: {
    vus: 5,
    duration: '30s',
  },
  // load / stress 는 후속 Task — 현재 비범위
};
