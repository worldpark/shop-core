/**
 * scenarios/payment-confirm.js
 *
 * 결제 확정 핫패스 시나리오 — cart add → order create → 결제 확정.
 *
 * 흐름:
 *   setup()  : 시드 실행 (seller 생성·상품 게시·variant·buyer N개) — setupSeed 재사용
 *   default(): VU별 전용 buyer로 cart add → order create → POST /orders/{orderId}/payment
 *   handleSummary(): setup_data(JWT 토큰) 제거, 메트릭 전용 JSON export
 *
 * 시나리오 설계 (방식 (a) — plan §3):
 *   각 VU 반복 = cart add → order create → 결제 확정.
 *   매 반복 신선한 PENDING 주문을 만들어 결제하므로 풀 소진 관리가 없다(자기 replenish).
 *
 * ★ 한계 정직 표기 (plan §3):
 *   이 시나리오는 order-create 단계(단일 variant PESSIMISTIC_WRITE)를 거치므로
 *   "종단(order→pay) throughput" 측정이다. 순수 결제 고립이 아님.
 *   결제 단계 자체 지연은 payment_confirm_duration Trend로 분리 계측한다.
 *
 * 프로파일 선택:
 *   -e PROFILE=smoke   (기본값, closed 모델: 5 VU × 30s)
 *   -e PROFILE=load    (open 모델: constant-arrival-rate 60rps × 1m)
 *   -e PROFILE=stress  (open 모델: ramping-arrival-rate 50→200rps, 한계 탐색)
 *
 * 환경변수:
 *   BASE_URL                — 기본 http://localhost:8080
 *   PROFILE                 — smoke (기본) | load | stress
 *   ADMIN_EMAIL             — 기본 admin@example.com
 *   ADMIN_PASSWORD          — 기본 Admin1234!
 *   RUN_TAG                 — 런별 유니크 prefix (미지정 시 uuidv4 자동)
 *   TOKEN_REFRESH_AFTER_SEC — 토큰 갱신 임계(초). 기본 1500(25분).
 *   SUMMARY_EXPORT_PATH     — 메트릭 전용 JSON 출력 경로 (토큰 제외)
 *
 * 커스텀 메트릭:
 *   payment_confirm_duration (Trend)   — 결제 POST 자체 지연 (ms) — plan §5
 *   payment_confirmed        (Counter) — 200 & status=="paid" 성공 카운트
 *   payment_conflict         (Counter) — 409 (상태 충돌) — 가시화만, threshold 없음
 *   payment_5xx              (Counter) — >=500 — threshold: count==0
 *
 * REST 계약 (plan §2):
 *   POST /api/v1/orders/{orderId}/payment  바디: {} (빈 바디)
 *   → method 기본 "mock", amount=null(주문 finalAmount 사용). amount 전송 금지(불일치 400).
 *   → 200 OK  + PaymentResponse { status:"paid", paymentId, pgTransactionId, paidAt }
 *   → 이미 paid인 주문 재결제 → 200 (멱등)
 *   → pending 아닌 다른 상태(취소 등) → 409
 *
 * ★ 운영 안전 게이트 (실행 전 필수):
 *   결제 확정 시 OrderCompletedEvent → order-completed 토픽 발행 → notification 주문확정 메일.
 *   notification이 smtp 모드이면 실 메일 대량 발송 사고가 발생한다.
 *   반드시 notification을 log 모드로 전환하거나 정지한 후 실행할 것.
 *   (plan §0, README §6 참조)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

import { PROFILES, BASE_URL, PAYMENT_THRESHOLDS } from '../lib/config.js';
import { authHeaders, getValidToken } from '../lib/auth.js';
import { setupSeed } from '../lib/seed.js';

// ---------------------------------------------------------------
// 프로파일 결정 — PROFILE 환경변수로 분기
// ---------------------------------------------------------------
const PROFILE = __ENV.PROFILE || 'smoke';

const p = PROFILES[PROFILE];
if (!p) {
  throw new Error(
    `[payment-confirm] 알 수 없는 PROFILE="${PROFILE}". 허용값: ${Object.keys(PROFILES).join('|')}`,
  );
}

// ---------------------------------------------------------------
// k6 options — order-create.js buildOptions 패턴 그대로 재사용
//
// closed 모델 (smoke):          top-level vus + duration
// open 모델 constant (load):    scenarios + constant-arrival-rate executor
// open 모델 ramping  (stress):  scenarios + ramping-arrival-rate executor
//   * top-level vus/duration 과 scenarios 혼용 금지 (k6 제약)
// ---------------------------------------------------------------
// summaryTrendStats: p99를 baseline JSON에 포함시키기 위해 명시 (k6 기본 export는 p99 미포함)
const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'];

function buildOptions(profile) {
  // payment-confirm 전용 thresholds로 교체 (PAYMENT_THRESHOLDS)
  const thresholds = PAYMENT_THRESHOLDS;

  if (profile.kind === 'ramping-arrival-rate') {
    return {
      summaryTrendStats: SUMMARY_TREND_STATS,
      scenarios: {
        payment_confirm: {
          executor: 'ramping-arrival-rate',
          startRate: profile.startRate,
          timeUnit: profile.timeUnit,
          stages: profile.stages,
          preAllocatedVUs: profile.preAllocatedVUs,
          maxVUs: profile.maxVUs,
        },
      },
      thresholds,
    };
  }
  if (profile.kind === 'arrival-rate') {
    return {
      summaryTrendStats: SUMMARY_TREND_STATS,
      scenarios: {
        payment_confirm: {
          executor: 'constant-arrival-rate',
          rate: profile.rate,
          timeUnit: profile.timeUnit,
          duration: profile.duration,
          preAllocatedVUs: profile.preAllocatedVUs,
          maxVUs: profile.maxVUs,
        },
      },
      thresholds,
    };
  }
  // closed 모델 (smoke)
  return {
    summaryTrendStats: SUMMARY_TREND_STATS,
    vus: profile.vus,
    duration: profile.duration,
    thresholds,
  };
}

export const options = buildOptions(p);

// ---------------------------------------------------------------
// 커스텀 메트릭 (plan §5)
// ---------------------------------------------------------------
const paymentConfirmDuration = new Trend('payment_confirm_duration', true); // ms, 결제 POST 자체 지연
const paymentConfirmed       = new Counter('payment_confirmed');             // 200 & status=="paid"
const paymentConflict        = new Counter('payment_conflict');              // 409 — 가시화만 (threshold 없음)
const payment5xx             = new Counter('payment_5xx');                   // >=500 — threshold: count==0

// ---------------------------------------------------------------
// setup() — 런 1회, VU 공유 데이터 생성
// ---------------------------------------------------------------
export function setup() {
  // buyer 수: open 모델(arrival-rate/ramping)은 maxVUs까지 VU가 늘어나므로 maxVUs 기준으로 시드
  // closed 모델은 vus 기준 (order-create.js와 동일 패턴)
  const buyerCount = p.maxVUs || p.vus;
  return setupSeed(buyerCount);
}

// ---------------------------------------------------------------
// default() — VU 본문 (cart add → order create → 결제 확정)
// ---------------------------------------------------------------
export default function (data) {
  // VU별 전용 buyer 선택 (plan §3: buyer = data.buyers[(__VU-1)%data.buyers.length])
  // 카트는 사용자 단위 — 주문 생성이 카트를 비움
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
    // 장바구니 실패 시 이후 단계 건너뜀 (잘못된 부하 계산 방지)
    if (cartRes.status >= 500) {
      payment5xx.add(1);
    }
    return;
  }

  // ---- 2. 주문 생성 --------------------------------------------
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

  const orderOk = check(orderRes, {
    'order create 201': (r) => r.status === 201,
  });

  if (!orderOk) {
    // 주문 생성 실패 시 결제 단계 건너뜀 (잘못된 부하 계산 방지)
    if (orderRes.status >= 500) {
      payment5xx.add(1);
    }
    return;
  }

  // orderId 추출 — order create 응답에서 (plan §2, §3)
  let orderId;
  try {
    const orderBody = JSON.parse(orderRes.body);
    orderId = orderBody.orderId;
  } catch (e) {
    // JSON 파싱 실패는 서버 오류에 준하므로 카운트 후 스킵
    payment5xx.add(1);
    return;
  }

  if (!orderId) {
    // orderId 없음 — 응답 계약 위반, 결제 불가, 카운트 후 스킵
    payment5xx.add(1);
    return;
  }

  // ---- 3. 결제 확정 (plan §2, §3) -----------------------------
  // POST /api/v1/orders/{orderId}/payment  바디: {} (빈 바디)
  // amount는 보내지 않음 — finalAmount와 불일치 시 400 발생 (plan §2)
  // method 기본 "mock" (서버 기본값 적용)
  const paymentStart = Date.now();
  const paymentRes = http.post(
    `${BASE_URL}/api/v1/orders/${orderId}/payment`,
    JSON.stringify({}),
    headers,
  );
  paymentConfirmDuration.add(Date.now() - paymentStart);

  const paymentOk = check(paymentRes, {
    'payment confirm 200': (r) => r.status === 200,
  });

  if (paymentOk) {
    // status=="paid" 여부도 확인해 커스텀 카운터에 반영
    let paid = false;
    try {
      const payBody = JSON.parse(paymentRes.body);
      paid = payBody.status === 'paid';
    } catch (_) {
      // 파싱 실패는 무시 — status 200 자체는 성공으로 기록
    }
    if (paid) {
      paymentConfirmed.add(1);
    }
  } else if (paymentRes.status === 409) {
    // 409: 상태 충돌 (주문이 pending이 아닌 상태 — cancel-vs-pay 선례 Task 033)
    // 가시화용, threshold 없음 (plan §5)
    paymentConflict.add(1);
  } else if (paymentRes.status >= 500) {
    // 5xx: 락/발행 붕괴 징후 — threshold: count==0
    payment5xx.add(1);
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
//   k6 run -e SUMMARY_EXPORT_PATH=build/k6/payment-confirm-load.json ...
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
