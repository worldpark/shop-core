/**
 * profiles/load.js
 *
 * load 프로파일 — 목표 RPS를 고정 가압하는 open 모델(constant-arrival-rate).
 * executor: constant-arrival-rate → 응답 시간과 무관하게 목표 RPS를 유지하려 시도.
 * 못 채우면 dropped_iterations 로 가시화(침묵 저부하 방지).
 *
 * 시나리오 본문은 scenarios/order-create.js 가 담당하며,
 * 이 파일은 options 만 export한다(smoke.js와 동일 패턴).
 *
 * 사용 (JSON 산출은 SUMMARY_EXPORT_PATH 사용 — handleSummary가 토큰 제외):
 *   k6 run -e PROFILE=load -e SUMMARY_EXPORT_PATH=build/k6/order-create-load.json \
 *     shop-core/perf/k6/scenarios/order-create.js
 *
 * 확정 목표 RPS: 60 rps × 1분 (포화점 아래 — 지속가능 운영수준. flaky 완화로 100→60 하향)
 *   - 앱 처리 상한 ≈ 90~100 orders/s. 100rps(상한 근처)는 open 모델 VU 과투입으로 p95 18~143ms flaky → load 부적합.
 *   - 60rps(채택): VU ~5개로 안정(에스컬레이션 없음), p95≈22ms·p99≈35~54ms·dropped=0 (2026-06-16 baseline JSON).
 *   - 200rps: dropped=71/s, p95=634ms / 300rps: dropped=138/s, p95=886ms — 한계 초과(stress 영역).
 *   한계·붕괴점(knee≈120rps) 탐색은 stress(003)가 담당 — load와 역할 분리.
 */

import { PROFILES, LOAD_THRESHOLDS } from '../lib/config.js';

const p = PROFILES.load;

export const options = {
  scenarios: {
    order_create: {
      executor: 'constant-arrival-rate',
      rate: p.rate,
      timeUnit: p.timeUnit,
      duration: p.duration,
      preAllocatedVUs: p.preAllocatedVUs,
      maxVUs: p.maxVUs,
    },
  },
  thresholds: LOAD_THRESHOLDS,
};
