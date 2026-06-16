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
 * 확정 목표 RPS: 100 rps × 1분 (2026-06-16 §6.5 실측 확정, 깨끗한 DB 기준 2026-06-16 재확정)
 *   - 100rps: p95=18ms, p99=33~62ms(baseline JSON 61.54ms, 런별 변동), dropped=0/s (깨끗한 DB, 2026-06-16 재측정).
 *   - 200rps: dropped=71/s, p95=634ms — 한계 초과(stress 영역).
 *   - 300rps: dropped=138/s, p95=886ms — 완전 한계 초과.
 *   앱 실제 처리 상한선 ≈ 90~100 orders/s.
 *   한계 탐색은 stress(003)으로 이양.
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
