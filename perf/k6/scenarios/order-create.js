/**
 * scenarios/order-create.js
 *
 * 1순위 핫패스 시나리오 — cart add → order create.
 *
 * 흐름:
 *   setup()  : 시드 실행 (seller 생성·상품 게시·variant·buyer N개)
 *   default(): VU별 전용 buyer로 cart add → order create + 커스텀 메트릭
 *   teardown(): 현재 비범위 (self-clean은 후속 Task)
 *
 * 프로파일 선택:
 *   -e PROFILE=smoke   (기본값, closed 모델: 5 VU × 30s)
 *   -e PROFILE=load    (open 모델: constant-arrival-rate 100rps × 1m)
 *   -e PROFILE=stress  (open 모델: ramping-arrival-rate 50→200rps, 한계 탐색)
 *
 * 환경변수:
 *   BASE_URL                — 기본 http://localhost:8080
 *   PROFILE                 — smoke (기본) | load | stress
 *   ADMIN_EMAIL             — 기본 admin@example.com
 *   ADMIN_PASSWORD          — 기본 Admin1234!
 *   RUN_TAG                 — 런별 유니크 prefix (미지정 시 uuidv4 자동)
 *   TOKEN_REFRESH_AFTER_SEC — 토큰 갱신 임계(초). 기본 1500(25분). 5로 지정하면 짧은 런에서도 갱신 발화.
 *
 * 커스텀 메트릭:
 *   order_created  (Counter) — 201 성공 카운트
 *   order_conflict (Counter) — 409 (낙관 충돌·재고 부족) 가시화용
 *   order_5xx      (Counter) — >=500 (락 붕괴 징후, threshold: count==0)
 *   order_create_duration (Trend) — order POST 자체 지연 (선택)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

import { PROFILES, BASE_URL } from '../lib/config.js';
import { authHeaders, getValidToken } from '../lib/auth.js';
import { setupSeed } from '../lib/seed.js';

// ---------------------------------------------------------------
// 프로파일 결정 — PROFILE 환경변수로 분기
// ---------------------------------------------------------------
const PROFILE = __ENV.PROFILE || 'smoke';

const p = PROFILES[PROFILE];
if (!p) {
  throw new Error(
    `[order-create] 알 수 없는 PROFILE="${PROFILE}". 허용값: ${Object.keys(PROFILES).join('|')}`,
  );
}

// ---------------------------------------------------------------
// k6 options — closed(vus+duration) / open(arrival-rate) 분기
//
// closed 모델 (smoke):          top-level vus + duration
// open 모델 constant (load):    scenarios + constant-arrival-rate executor
// open 모델 ramping  (stress):  scenarios + ramping-arrival-rate executor
//   * top-level vus/duration 과 scenarios 혼용 금지 (k6 제약)
// ---------------------------------------------------------------
// summaryTrendStats: p99를 baseline JSON에 포함시키기 위해 명시 (k6 기본 export는 p99 미포함)
const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'];

function buildOptions(profile) {
  if (profile.kind === 'ramping-arrival-rate') {
    return {
      summaryTrendStats: SUMMARY_TREND_STATS,
      scenarios: {
        order_create: {
          executor: 'ramping-arrival-rate',
          startRate: profile.startRate,
          timeUnit: profile.timeUnit,
          stages: profile.stages,
          preAllocatedVUs: profile.preAllocatedVUs,
          maxVUs: profile.maxVUs,
        },
      },
      thresholds: profile.thresholds,
    };
  }
  if (profile.kind === 'arrival-rate') {
    return {
      summaryTrendStats: SUMMARY_TREND_STATS,
      scenarios: {
        order_create: {
          executor: 'constant-arrival-rate',
          rate: profile.rate,
          timeUnit: profile.timeUnit,
          duration: profile.duration,
          preAllocatedVUs: profile.preAllocatedVUs,
          maxVUs: profile.maxVUs,
        },
      },
      thresholds: profile.thresholds,
    };
  }
  // closed 모델 (smoke)
  return {
    summaryTrendStats: SUMMARY_TREND_STATS,
    vus: profile.vus,
    duration: profile.duration,
    thresholds: profile.thresholds,
  };
}

export const options = buildOptions(p);

// ---------------------------------------------------------------
// 커스텀 메트릭
// ---------------------------------------------------------------
const orderCreated       = new Counter('order_created');   // 201 성공
const orderConflict      = new Counter('order_conflict');  // 409 — 가시화만 (threshold 없음)
const order5xx           = new Counter('order_5xx');       // >=500 — threshold: count==0
const orderCreateDuration = new Trend('order_create_duration', true); // ms, 선택

// ---------------------------------------------------------------
// setup() — 런 1회, VU 공유 데이터 생성
// ---------------------------------------------------------------
export function setup() {
  // buyer 수: open 모델(arrival-rate/ramping)은 maxVUs까지 VU가 늘어나므로 maxVUs 기준으로 시드
  // closed 모델은 vus 기준 (기존과 동일)
  // — VU별 전용 buyer로 카트 교차오염 방지, (__VU-1)%buyers.length 매핑 유지
  const buyerCount = p.maxVUs || p.vus;
  return setupSeed(buyerCount);
}

// ---------------------------------------------------------------
// default() — VU 본문 (흐름 무변경)
// ---------------------------------------------------------------
export default function (data) {
  // VU별 전용 buyer 선택 (카트는 사용자 단위 — 주문 생성이 카트를 비움)
  const buyerIndex = (__VU - 1) % data.buyers.length;
  const buyer      = data.buyers[buyerIndex];
  // getValidToken: VU-로컬 캐시 확인 후 TOKEN_REFRESH_AFTER_SEC 초과 시 refresh 호출.
  // smoke/load 짧은 런(기본 임계 1500초)에서는 갱신이 발화하지 않아 기존 동작과 동일.
  const token      = getValidToken(buyer);
  const headers    = authHeaders(token);

  // ---- 1. 장바구니 담기 ----------------------------------------
  const cartRes = http.post(
    `${BASE_URL}/api/v1/cart/items`,
    JSON.stringify({ variantId: data.variantId, quantity: 1 }),
    headers,
  );

  check(cartRes, {
    'cart add 200': (r) => r.status === 200,
  });

  if (cartRes.status !== 200) {
    // 장바구니 실패 시 주문 단계 건너뜀 (잘못된 부하 계산 방지)
    if (cartRes.status >= 500) {
      order5xx.add(1);
    }
    return;
  }

  // ---- 2. 주문 생성 --------------------------------------------
  const orderStart = Date.now();
  const orderRes = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({
      recipient: 'Perf Tester',
      phone: '010-0000-0000',
      postcode: '12345',
      address1: 'k6 Perf Street 1',
      address2: null,
      userCouponId: null,
    }),
    headers,
  );
  orderCreateDuration.add(Date.now() - orderStart);

  const orderOk = check(orderRes, {
    'order create 201': (r) => r.status === 201,
  });

  if (orderOk) {
    orderCreated.add(1);
  } else if (orderRes.status === 409) {
    orderConflict.add(1);
  } else if (orderRes.status >= 500) {
    order5xx.add(1);
  }
}

// ---------------------------------------------------------------
// handleSummary() — setup_data(JWT 토큰) 제외, 메트릭 전용 JSON export
//
// k6의 --summary-export는 setup() 반환값(buyer 토큰 포함)을 setup_data 필드에
// 자동 포함한다. 이를 방지하기 위해 handleSummary에서 setup_data를 제거한
// 메트릭 전용 JSON을 SUMMARY_EXPORT_PATH 환경변수 경로로 내보낸다.
//
// 사용 예:
//   k6 run -e SUMMARY_EXPORT_PATH=build/k6/order-create-load.json ...
//
// handleSummary가 존재하면 --summary-export는 무시된다(k6 설계).
// ---------------------------------------------------------------
export function handleSummary(data) {
  // setup_data 제거 — JWT 토큰 노출 방지
  const safeData = {
    root_group: data.root_group,
    metrics: data.metrics,
    // setup_data는 의도적으로 제외
  };

  const outputs = {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };

  // SUMMARY_EXPORT_PATH 환경변수로 파일 경로 지정 (--summary-export 대체)
  const exportPath = __ENV.SUMMARY_EXPORT_PATH;
  if (exportPath) {
    outputs[exportPath] = JSON.stringify(safeData, null, 2);
  }

  return outputs;
}
