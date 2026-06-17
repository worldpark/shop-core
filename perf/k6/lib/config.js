/**
 * lib/config.js
 *
 * 공통 설정 모듈 — BASE_URL, PROFILE 분기, thresholds, 시드 상수.
 * 모든 시나리오·프로파일은 이 파일을 import해 중복을 제거한다.
 *
 * 환경변수:
 *   BASE_URL                — 가압 대상 앱 루트 (기본: http://localhost:8080)
 *   PROFILE                 — 부하 프로파일 선택 smoke|load|stress|conc|saturate (기본: smoke)
 *   ADMIN_EMAIL             — admin 계정 이메일 (기본: admin@example.com)
 *   ADMIN_PASSWORD          — admin 계정 비밀번호 (기본: Admin1234!)
 *   RUN_TAG                 — 런별 유니크 prefix (미지정 시 uuidv4 자동 생성)
 *   TOKEN_REFRESH_AFTER_SEC — 토큰 갱신 발화 임계(초). 기본 1500(25분). stress 런 중 갱신
 *                             기능 검증 시 5 등 짧게 지정. (auth.js getValidToken 참조)
 *   CONC_VUS                — conc 프로파일 VU 수 (기본: 300). A/B 측정 시 100/300/500 스윕.
 *   CONC_DURATION           — conc 프로파일 지속 시간 (기본: 40s).
 *   ORDER_VARIANT_COUNT     — order-create 시드의 variant 수 (기본: 1).
 *                             1=단일 variant(기존 베이스라인 재현), >1=분산(행 락 경합 분산).
 *                             order-create 측정 전용 — payment-confirm/coupon-apply 실행 시엔 지정 말 것.
 *   SATURATE_PEAK_RPS       — saturate 프로파일의 피크 RPS (기본: 600).
 *                             첫 광역 탐색은 600, 천장이 좁혀지면 조정.
 *                             주의: saturate는 ORDER_VARIANT_COUNT≥50 분산 전제.
 *                             단일 variant(N=1)로 실행하면 행 락 산물이지 일반 트래픽 천장이 아님.
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

  // ---- order-create 다중 variant 분산 (order-create.js 전용) ---------
  /**
   * order-create 시드에서 생성할 variant 수.
   * 1 = 단일 variant(기존 베이스라인 재현 — 전 VU가 같은 행에 PESSIMISTIC_WRITE 직렬화).
   * >1 = 분산(variant 수만큼 상품을 별도 생성해 행 락 경합을 분산).
   *
   * 주의: ORDER_VARIANT_COUNT는 order-create 측정 전용이다.
   *   payment-confirm / coupon-apply 실행 시에는 이 env를 지정하지 말 것.
   *   지정해도 결과는 동일(variantIds[0]만 사용)이지만 setup 시간이 늘어난다.
   *
   * 분산 측정 시작 후보값: 50 (setupCatalogSeed 선례와 동일).
   */
  ORDER_VARIANT_COUNT: __ENV.ORDER_VARIANT_COUNT ? Number(__ENV.ORDER_VARIANT_COUNT) : 1,

  // ---- 카탈로그 읽기 시드 상수 (catalog-read.js 전용) ---------------
  /** catalog-read 시드에서 등록할 공개 상품 수 (읽기 부하 다양성 확보). */
  CATALOG_PRODUCT_COUNT: 50,
  /** 카탈로그 상품명 prefix (setupCatalogSeed 전용 — setupSeed와 네임스페이스 분리). */
  CATALOG_PRODUCT_NAME_PREFIX: 'PERF-Catalog',
  /** 카탈로그 SKU prefix */
  CATALOG_SKU_PREFIX: 'PERF-CSKU',

  // ---- 쿠폰 시드 상수 (coupon-apply.js 전용) ----------------------
  /** 쿠폰 코드 prefix. 런별 유니크 prefix와 조합해 충돌 방지. */
  COUPON_CODE_PREFIX: 'PERF-COUP',
  /** 할인 유형 — 고정 금액 */
  COUPON_DISCOUNT_TYPE: 'fixed',
  /** 할인 금액 (BigDecimal → 문자열) */
  COUPON_VALUE: '1000',
  /** 최소 주문 금액 — 0이면 제한 없음 */
  COUPON_MIN_ORDER_AMOUNT: '0',
  /** 쿠폰 사용 횟수 제한 — null=무제한 (단일사용 직렬화 경합 시나리오) */
  COUPON_USAGE_LIMIT: null,
  /** 쿠폰 유효 시작 (과거 고정값 → now 항상 포함) */
  COUPON_STARTS_AT: '2000-01-01T00:00:00Z',
  /** 쿠폰 유효 종료 (미래 고정값) */
  COUPON_ENDS_AT: '2099-12-31T23:59:59Z',
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
// payment thresholds — payment-confirm.js 전용
//
// 베이스라인: 2026-06-16 (깨끗한 DB, notification 정지, load 60rps×1m, payment-confirm.js).
//   확정 출처 = baselines/payment-confirm-load.json. 관측: payment_confirm_duration p95=18ms,
//   p99=24ms, payment_5xx=0, http_req_failed=0%, payment_confirmed ~56/s.
//   결제 POST는 빠르다(Outbox 이벤트 외부화는 커밋 후 비동기라 POST 응답에 포함 안 됨 — 행 락+payment INSERT만).
//   서로 다른 주문을 결제하므로 결제 행 락은 경합 없음 → 안정적. 임계 = 관측×~2.7/3.3(smoke/load 선례 일관).
//
//   주의: 본 시나리오(방식 a)는 cart→order→pay 종단이라 order-create의 단일 variant 락이 함께 걸린다.
//   payment_confirm_duration은 결제 단계만 분리 계측한 값이다(종단 http_req_duration과 구분).
// ---------------------------------------------------------------
export const PAYMENT_THRESHOLDS = {
  // HTTP 에러율 1% 미만 (4xx/5xx 포함)
  http_req_failed: ['rate<0.01'],

  // payment 5xx — 락/Outbox 발행 붕괴 징후. 반드시 0이어야 함.
  payment_5xx: ['count==0'],

  // payment_confirm_duration — 결제 POST 자체 지연(결제 단계 분리 계측).
  // 베이스라인 p95=18ms·p99=24ms × 여유율.
  payment_confirm_duration: [
    'p(95)<50', // 베이스라인 p95≈18ms × ~2.7 여유 → 50ms
    'p(99)<80', // 베이스라인 p99≈24ms × ~3.3 여유 → 80ms
  ],

  // payment_conflict(409)은 임계로 죽이지 않음.
  // 상태 충돌(취소 등)은 정상 비즈니스 흐름이므로 Counter로 가시화만.
};

// ---------------------------------------------------------------
// coupon thresholds — coupon-apply.js 전용
//
// 쿠폰 사용 동시성(중복 사용 방지) 경로 가압 시나리오 (Task 005).
// 409(쿠폰 충돌)는 http.expectedStatuses(200,201,409)로 http_req_failed에서 제외.
// 베이스라인 2026-06-16 확정(깨끗한 DB, notification 정지, load 60rps×1m 2회):
//   coupon_5xx=0, http_req_failed=0%, coupon_applied ≤ buyerCount(이중사용 0),
//   coupon_order_duration p95=19~23ms·p99=52~138ms(409 다수+성공경로 소표본 꼬리 혼합).
// ---------------------------------------------------------------
export const COUPON_THRESHOLDS = {
  // HTTP 에러율 1% 미만 (409는 expectedStatuses로 제외됨 — 진짜 5xx/타임아웃만 잡힘)
  http_req_failed: ['rate<0.01'],

  // 쿠폰 5xx — 직렬화 붕괴(낙관 락/제약 위반이 500으로 새는) 징후. 반드시 0이어야 함.
  coupon_5xx: ['count==0'],

  // coupon_order_duration — 쿠폰 적용 주문 POST 자체 지연(ms).
  // 베이스라인 2026-06-16(깨끗한 DB, notification 정지, load 60rps×1m): p95=23ms, p99=138ms.
  //   대부분 409(이미 사용, fast ~11ms)에 소수 201(성공경로=order-create 변수 락 포함) 꼬리가 섞여
  //   p99가 성공경로 소표본 꼬리를 반영(변동 큼) → p99는 여유를 크게 둔다.
  coupon_order_duration: [
    'p(95)<60',  // 베이스라인 p95≈23ms × ~2.6 여유 → 60ms
    'p(99)<300', // 베이스라인 p99≈138ms × ~2.2 (성공경로 소표본 꼬리 변동 흡수)
  ],

  // coupon_conflict(409)은 임계 없음 — 중복 사용 차단의 정상 흐름이므로 가시화만.
  // coupon_applied(201 & discountAmount>0)도 임계 없음 — 이중사용 0 신호로 해석.
};

// ---------------------------------------------------------------
// saturate thresholds — 진단용(느슨), 천장·병목 탐색 목적
//
// saturate는 "어디서 포화되나"를 측정하는 것이 목적이므로
// p95/dropped_iterations 임계로 런을 죽이지 않는다(측정 곡선 대상이므로).
// 단, 과부하라도 5xx(락 붕괴·서버 오류)는 반드시 0이어야 함.
// http_req_failed: 과부하 타임아웃이 실패로 잡힐 수 있어 임계 없음 — 곡선의 일부로 기록.
//
// 풀 스윕(SHOP_CORE_HIKARI_MAX_POOL=10/30/50)과 서버측 스크랩(scrape-metrics.py)을
// 병행해 dropped/p95 무릎 + hikaricp_connections_pending / process_cpu_usage로
// 풀/CPU/DB 병목을 교차 판정한다.
// ---------------------------------------------------------------
export const SATURATE_THRESHOLDS = {
  // order_5xx — 락 붕괴·서버 오류 징후. 과부하라도 반드시 0이어야 함.
  order_5xx: ['count==0'],
  // http_req_duration / dropped_iterations / http_req_failed 임계 없음 — 측정 대상(곡선).
};

// ---------------------------------------------------------------
// read thresholds — catalog-read.js (가상스레드 A/B 측정, Task 006)
//
// 목적: 플랫폼 스레드(200)를 초과하는 동시성(conc 프로파일 300VU)에서
//   읽기 경로(GET /api/v1/products, GET /api/v1/products/{id})를 가압해
//   VT vs 플랫폼 스레드 A/B 비교 곡선을 측정한다.
// — "pass/fail SLA" 가 아니라 "진단용 곡선 측정" 이 목적이므로
//   http_req_duration 임계는 없다. 비교는 JSON 아티팩트의 p95/p99 수치로 한다.
// — 단, 에러율(5xx)과 read_5xx는 임계로 잡는다.
//   공개 읽기는 락 없음 — 5xx가 나면 서버 오류로 즉시 조사.
// ---------------------------------------------------------------
export const READ_THRESHOLDS = {
  // HTTP 에러율 1% 미만 (4xx/5xx 포함). 공개 GET은 401/403 없으므로 실질=5xx+타임아웃.
  http_req_failed: ['rate<0.01'],

  // 읽기 5xx — 서버 오류 징후. 반드시 0이어야 함.
  read_5xx: ['count==0'],

  // http_req_duration 임계 없음 — VT vs 플랫폼 스레드 곡선 측정이 목적.
  // p95/p99는 JSON 아티팩트(SUMMARY_EXPORT_PATH)로 추출해 비교표에 기록한다.
};

// ---------------------------------------------------------------
// 하위 호환: 기존 THRESHOLDS 심볼 유지 (smoke.js 가 참조)
// smoke.js 는 THRESHOLDS → SMOKE_THRESHOLDS 로 마이그레이션 권장
// ---------------------------------------------------------------
export const THRESHOLDS = SMOKE_THRESHOLDS;

// ---------------------------------------------------------------
// saturate stages 헬퍼 — SATURATE_PEAK_RPS 기반으로 결정적 생성
//
// 설계 원칙:
//   1. random/Date.now 미사용 — 동일 env 값이면 항상 동일 stages 생성(재현성).
//   2. 100rps씩 계단식 점증 + 각 계단 40s + {target:0, duration:'15s'} 쿨다운.
//   3. 200rps 아래는 stress가 이미 담당 — saturate는 200rps 이상부터 시작.
//      (startRate는 stress의 피크와 겹치지 않도록 200으로 고정)
//   4. 계단 수 = Math.ceil((peak - 100) / 100) — peak 미만까지 100씩 계단 후 peak에서 마무리.
//   예: peak=600 → 100→200→300→400→500→600(각 40s) → 0(15s), 계단 6개 + 쿨다운 = 총 ~4m 15s.
//   예: peak=400 → 100→200→300→400(각 40s) → 0(15s), 계단 4개 + 쿨다운 = 총 ~2m 55s.
// ---------------------------------------------------------------
function buildSaturateStages(peakRps) {
  const stages = [];
  // 100rps씩 계단식 점증 (100 미만이면 첫 계단 = peak 자체)
  const step = 100;
  // 200rps부터 peak까지(또는 peak 자체)를 step씩
  let target = step;
  while (target < peakRps) {
    stages.push({ target: target, duration: '40s' });
    target += step;
  }
  // 마지막 계단: peak RPS
  stages.push({ target: peakRps, duration: '40s' });
  // 쿨다운
  stages.push({ target: 0, duration: '15s' });
  return stages;
}

// ---------------------------------------------------------------
// 프로파일별 VU/duration 설정
// profiles/smoke.js, profiles/load.js 가 import해서 사용한다.
//
// kind 필드:
//   'closed'            — vus + duration (기존 모델)
//   'arrival-rate'      — constant-arrival-rate executor (open 모델)
//   'ramping-arrival-rate' — ramping-arrival-rate executor (open 모델)
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

  conc: {
    // closed 모델 — VU 수만큼 동시 요청을 항상 유지.
    // open 모델(arrival-rate)은 빠른 읽기에서 200 동시를 만들려면 비현실적 높은 rate가 필요.
    // closed 모델은 duration 내내 vus 개의 요청이 중단 없이 순환해 "진짜 동시성"을 만든다.
    //
    // 목표: 플랫폼 스레드(기본 200)를 넘겨 큐잉시키면 VT 이득이 가시화됨.
    //   - CONC_VUS=100 : 플랫폼 스레드(200) 미포화 — 베이스라인
    //   - CONC_VUS=300 : 플랫폼 스레드(200) 포화 → 큐잉 발생 — 핵심 측정점 (기본값)
    //   - CONC_VUS=500 : 큐잉 심화 — 포화 곡선 측정
    // CONC_VUS 환경변수로 런타임 조절 가능 (A/B 매트릭스 §3 측정 시 메인이 조정).
    //
    // duration: CONC_DURATION 환경변수로 오버라이드 가능 (기본 40s).
    //   40s = 워밍업(~5s) + 안정 측정(~30s) + 쿨다운 여유.
    //   읽기 경로는 Outbox/Kafka 없으므로 짧아도 충분한 샘플 확보 가능.
    kind: 'closed',
    vus: __ENV.CONC_VUS ? Number(__ENV.CONC_VUS) : 300,
    duration: __ENV.CONC_DURATION || '40s',
    thresholds: READ_THRESHOLDS,
  },

  saturate: {
    // ramping-arrival-rate 모델 — 200rps를 넘어 점증해 분산 order-create 처리량 천장·병목을 탐색.
    //
    // 설계 근거 (report 002 §4.2):
    //   분산(N=50) stress(50→200rps)가 p95=21ms·dropped=0으로 완주 → 200rps에서 미포화.
    //   saturate는 200rps 위로 점증해 dropped 급증·p95 무릎이 나타나는 RPS = 실제 처리량 천장.
    //
    // 분산 전제: ORDER_VARIANT_COUNT≥50과 함께만 의미.
    //   단일 variant(N=1)는 행 락 직렬화 산물 — 일반 트래픽 천장 아님.
    //   실행 예: -e PROFILE=saturate -e ORDER_VARIANT_COUNT=50 -e SATURATE_PEAK_RPS=600
    //
    // VU 풀:
    //   preAllocatedVUs=100 — 피크까지 점증하는 동안 VU 부족이 인위적 병목이 되지 않게 충분히.
    //   maxVUs=400          — 메모리 가드: 무한 VU 확장 금지. stdout "Insufficient VUs" 경고로
    //                         VU 부족 vs 앱 한계를 구분. maxVUs 초과 dropped ≠ 앱 한계.
    //
    // stages: SATURATE_PEAK_RPS env(기본 600)에 따라 buildSaturateStages()가 결정적 생성.
    //   100→200→...→peak(각 40s) + {target:0, duration:'15s'} 쿨다운.
    //
    // thresholds: 진단용(느슨) — order_5xx count==0만 게이트.
    //   http_req_duration / dropped_iterations 임계 없음 — 곡선 측정이 목적.
    //
    // 병목 분류:
    //   이 프로파일 단독으로는 병목 위치(풀/CPU/DB) 판별 불가.
    //   SHOP_CORE_HIKARI_MAX_POOL=10/30/50 풀 스윕 + scrape-metrics.py(서버측 hikari/CPU 타임시리즈)
    //   교차 판정으로 분류한다. README §5-5 참조.
    kind: 'ramping-arrival-rate',
    startRate: 50,
    timeUnit: '1s',
    stages: buildSaturateStages(__ENV.SATURATE_PEAK_RPS ? Number(__ENV.SATURATE_PEAK_RPS) : 600),
    preAllocatedVUs: 100,
    maxVUs: 400,
    thresholds: SATURATE_THRESHOLDS,
  },
};
