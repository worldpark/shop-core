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
 *   -e PROFILE=smoke  (기본값, 5 VU × 30s)
 *
 * 환경변수:
 *   BASE_URL       — 기본 http://localhost:8080
 *   PROFILE        — smoke (기본)
 *   ADMIN_EMAIL    — 기본 admin@example.com
 *   ADMIN_PASSWORD — 기본 Admin1234!
 *   RUN_TAG        — 런별 유니크 prefix (미지정 시 uuidv4 자동)
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

import { THRESHOLDS, PROFILES, BASE_URL } from '../lib/config.js';
import { authHeaders } from '../lib/auth.js';
import { setupSeed } from '../lib/seed.js';

// ---------------------------------------------------------------
// 프로파일 결정 — PROFILE 환경변수로 분기
// ---------------------------------------------------------------
const PROFILE = __ENV.PROFILE || 'smoke';

const profileConfig = PROFILES[PROFILE];
if (!profileConfig) {
  throw new Error(
    `[order-create] 알 수 없는 PROFILE="${PROFILE}". 허용값: ${Object.keys(PROFILES).join('|')}`,
  );
}

// ---------------------------------------------------------------
// k6 options — thresholds는 lib/config.js 에서 공통 관리
// ---------------------------------------------------------------
export const options = {
  vus: profileConfig.vus,
  duration: profileConfig.duration,
  thresholds: THRESHOLDS,
};

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
  // buyer 수 = 프로파일 최대 VU (VU별 전용 buyer로 카트 교차오염 방지)
  const buyerCount = profileConfig.vus;
  return setupSeed(buyerCount);
}

// ---------------------------------------------------------------
// default() — VU 본문
// ---------------------------------------------------------------
export default function (data) {
  // VU별 전용 buyer 선택 (카트는 사용자 단위 — 주문 생성이 카트를 비움)
  const buyerIndex = (__VU - 1) % data.buyers.length;
  const buyer      = data.buyers[buyerIndex];
  const headers    = authHeaders(buyer.token);

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
