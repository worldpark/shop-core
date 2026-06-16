/**
 * scenarios/coupon-apply.js
 *
 * 쿠폰 사용 동시성(중복 사용 방지) 경로 가압 시나리오 — Task 005.
 *
 * 목표:
 *   쿠폰 적용 주문에서 단일사용 직렬화(markUsedIfUnused)가 경합 상황에서 올바르게
 *   직렬화되는지(붕괴 아님)를 블랙박스로 확인한다:
 *   - coupon_5xx == 0 (5xx 없음 = 락/제약 붕괴 없음)
 *   - coupon_applied 총합 <= buyerCount (이중사용 없음 신호)
 *   - 충돌은 409로만 (coupon_conflict)
 *
 * 흐름:
 *   setup()  : setupCouponSeed() — seller/상품/variant/buyer + 쿠폰 생성 + buyer별 쿠폰 발급(userCouponId)
 *   default(): VU별 전용 buyer로 cart add → POST /orders {userCouponId} → 201(첫 사용) or 409(이미 사용)
 *   handleSummary(): setup_data(JWT 토큰·userCouponId) 제거, 메트릭 전용 JSON export
 *
 * 소비성 전제:
 *   userCoupon은 1회 사용하면 끝(이후 409). 각 VU 반복에서 동일 buyer의 쿠폰을 재시도하므로
 *   첫 성공 후에는 409(coupon_conflict)가 쌓인다. 이것이 이 시나리오의 정상 흐름이다.
 *
 * ★ 운영 안전 게이트 (실행 전 필수):
 *   - signup(buyer) → 환영 메일 발생
 *   - PENDING 주문 만료 자동취소 → 취소 메일 발생
 *   notification이 smtp 모드이면 실 메일이 대량 발송된다.
 *   반드시 notification을 log 모드로 전환하거나 프로세스 정지 후 실행할 것.
 *
 * 409 처리 (오탐 방지):
 *   init 단계에서 http.setResponseCallback(http.expectedStatuses(200, 201, 409)) 설정.
 *   → 409가 http_req_failed에서 제외됨(비즈니스 정상 흐름).
 *   → 진짜 오류(5xx·타임아웃)만 http_req_failed에 잡힘.
 *
 * 프로파일 선택:
 *   -e PROFILE=smoke   (기본값, closed 모델: 5 VU × 30s)
 *   -e PROFILE=load    (open 모델: constant-arrival-rate 60rps × 1m)
 *   -e PROFILE=stress  (open 모델: ramping-arrival-rate 50→200rps)
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
 *   coupon_applied         (Counter) — 201 & discountAmount>0 (쿠폰 적용 주문 성공)
 *   coupon_conflict        (Counter) — 409 (이미 사용된 쿠폰 — 중복 사용 차단 정상 흐름)
 *   coupon_5xx             (Counter) — >=500 (직렬화 붕괴 징후 — threshold: count==0)
 *   coupon_order_duration  (Trend)   — 쿠폰 적용 주문 POST 자체 지연 (ms)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

import { PROFILES, BASE_URL, COUPON_THRESHOLDS } from '../lib/config.js';
import { authHeaders, getValidToken } from '../lib/auth.js';
import { setupCouponSeed } from '../lib/seed.js';

// ---------------------------------------------------------------
// 409(쿠폰 충돌)를 http_req_failed에서 제외 — 비즈니스 정상 흐름 오탐 방지 (plan §5)
// expectedStatuses에 포함된 status는 http_req_failed로 카운트되지 않는다.
// ---------------------------------------------------------------
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

// ---------------------------------------------------------------
// 프로파일 결정 — PROFILE 환경변수로 분기
// ---------------------------------------------------------------
const PROFILE = __ENV.PROFILE || 'smoke';

const p = PROFILES[PROFILE];
if (!p) {
  throw new Error(
    `[coupon-apply] 알 수 없는 PROFILE="${PROFILE}". 허용값: ${Object.keys(PROFILES).join('|')}`,
  );
}

// ---------------------------------------------------------------
// k6 options — order-create.js / payment-confirm.js buildOptions 패턴 재사용
//
// closed 모델 (smoke):          top-level vus + duration
// open 모델 constant (load):    scenarios + constant-arrival-rate executor
// open 모델 ramping  (stress):  scenarios + ramping-arrival-rate executor
//   * top-level vus/duration 과 scenarios 혼용 금지 (k6 제약)
// ---------------------------------------------------------------
// summaryTrendStats: p99를 baseline JSON에 포함시키기 위해 명시 (k6 기본 export는 p99 미포함)
const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'];

function buildOptions(profile) {
  // coupon-apply 전용 thresholds (COUPON_THRESHOLDS) 로 교체
  const thresholds = COUPON_THRESHOLDS;

  if (profile.kind === 'ramping-arrival-rate') {
    return {
      summaryTrendStats: SUMMARY_TREND_STATS,
      scenarios: {
        coupon_apply: {
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
        coupon_apply: {
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
// 커스텀 메트릭 (plan §6)
// ---------------------------------------------------------------
const couponApplied       = new Counter('coupon_applied');       // 201 & discountAmount>0
const couponConflict      = new Counter('coupon_conflict');      // 409 — 가시화만 (threshold 없음)
const coupon5xx           = new Counter('coupon_5xx');           // >=500 — threshold: count==0
const couponOrderDuration = new Trend('coupon_order_duration', true); // ms, 쿠폰적용 주문 POST 지연

// ---------------------------------------------------------------
// setup() — 런 1회, VU 공유 데이터 생성
// ---------------------------------------------------------------
export function setup() {
  // buyer 수: open 모델(arrival-rate/ramping)은 maxVUs까지 VU가 늘어나므로 maxVUs 기준으로 시드
  // closed 모델은 vus 기준 (order-create.js 패턴과 동일)
  const buyerCount = p.maxVUs || p.vus;

  // setupCouponSeed: 기존 setupSeed + 쿠폰 생성 + buyer별 쿠폰 발급(userCouponId)
  // buyers 배열의 각 원소: { token, accessToken, refreshToken, issuedAt, email, userCouponId }
  return setupCouponSeed(buyerCount);
}

// ---------------------------------------------------------------
// default() — VU 본문 (cart add → 쿠폰 적용 주문 create)
// ---------------------------------------------------------------
export default function (data) {
  // VU별 전용 buyer 선택 — 카트는 사용자 단위, 주문이 카트를 비움
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
      coupon5xx.add(1);
    }
    return;
  }

  // ---- 2. 쿠폰 적용 주문 생성 ------------------------------------
  // userCouponId: setup에서 발급받은 buyer 전용 userCouponId (1인1매)
  // 첫 사용(미사용 쿠폰) → 201 + discountAmount>0 → coupon_applied++
  // 이후(이미 사용)      → 409 → coupon_conflict++ (정상: 중복 사용 차단)
  // >=500               → coupon_5xx++
  const orderStart = Date.now();
  const orderRes = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({
      recipient: 'Perf Tester',
      phone: '010-0000-0000',
      postcode: '12345',
      address1: 'k6 Perf Street 1',
      address2: null,
      userCouponId: buyer.userCouponId,
    }),
    headers,
  );
  couponOrderDuration.add(Date.now() - orderStart);

  // 201(첫 사용 성공)·409(이미 사용 — 정상 차단) 모두 "정상 처리"다(이 시나리오의 기대 결과).
  // 5xx/그 외만 비정상 → check 실패로 잡는다(409를 실패로 세면 checks%가 오해를 부름).
  check(orderRes, {
    'coupon order handled (201|409)': (r) => r.status === 201 || r.status === 409,
  });

  if (orderRes.status === 201) {
    // 201 성공 — discountAmount > 0 여부 확인(쿠폰 실제 적용 신호)
    let discountApplied = false;
    try {
      const orderBody = JSON.parse(orderRes.body);
      // discountAmount가 존재하고 양수(> 0)이면 쿠폰 적용 확인
      const discount = parseFloat(orderBody.discountAmount);
      discountApplied = !isNaN(discount) && discount > 0;
    } catch (_) {
      // JSON 파싱 실패 — 201 자체는 성공이므로 계속(discountApplied=false)
    }
    if (discountApplied) {
      couponApplied.add(1);
    }
  } else if (orderRes.status === 409) {
    // 409: 쿠폰 이미 사용(markUsedIfUnused 조건부 UPDATE → 영향행 0 → CouponConflictException)
    // 비즈니스 정상 흐름 — threshold 없음, 가시화만 (plan §6)
    couponConflict.add(1);
  } else if (orderRes.status >= 500) {
    // 5xx: 직렬화 붕괴(낙관 락/제약 위반이 500으로 새는) — threshold: count==0
    coupon5xx.add(1);
  }
}

// ---------------------------------------------------------------
// handleSummary() — setup_data(JWT 토큰·userCouponId) 제거, 메트릭 전용 JSON export
//
// k6의 --summary-export는 setup() 반환값(buyer 토큰·userCouponId 포함)을 setup_data 필드에
// 자동 포함한다. 이를 방지하기 위해 handleSummary에서 setup_data를 제거한
// 메트릭 전용 JSON을 SUMMARY_EXPORT_PATH 환경변수 경로로 내보낸다.
//
// 사용 예:
//   k6 run -e SUMMARY_EXPORT_PATH=build/k6/coupon-apply-load.json ...
//
// handleSummary가 존재하면 --summary-export는 무시된다(k6 설계).
// ---------------------------------------------------------------
export function handleSummary(data) {
  // setup_data 제거 — JWT 토큰·userCouponId 노출 방지
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
