/**
 * profiles/stress.js
 *
 * stress 프로파일 — 점증 부하(ramping-arrival-rate)로 SLO 붕괴점(knee)을 탐색한다.
 * executor: ramping-arrival-rate → 단계별 목표 RPS를 자동 점증.
 *   못 채우면 dropped_iterations 로 가시화(침묵 저부하 방지).
 *
 * 선행 탐색(Task 002):
 *   100rps → p95=18ms, dropped=0    (SLO 충족)
 *   200rps → p95=634ms, dropped=71/s (붕괴)
 *   → knee는 100~200rps 사이. 이 구간을 20rps 스텝으로 정밀 점증.
 *
 * Task 003 실측 knee (2026-06-16, 깨끗한 DB):
 *   - 100rps 구간(0~30s): ~100 iters/s, VU 3~4개 — 정상 (load SLO 충족 확인)
 *   - 120~130rps 구간(30s~1m30s): ~113~115 iters/s, VU 3~13개 — 안정적 처리
 *   - ~130rps 초과(1m30s~2m06s): VU가 197→200으로 급증, "Insufficient VUs" 경고 발생
 *     → VU 풀 포화 시작. 처리 속도가 목표 RPS를 못 따라가기 시작.
 *   - 140~200rps 구간(2m07s~4m30s): 200 VUs 고정, dropped_iterations 누적(총 8442회/28.5/s)
 *   - 쿨다운 진입(~4m42s): iters/s 192→178→165→152→138 급감
 *   aggregate 지표: http_req_duration p95=1.1s / p99=1.4s / http_req_failed=0% / order_5xx=0
 *
 *   knee(SLO 마지막 유지 RPS): 약 120rps
 *   → 120rps 이하: dropped 없이 3~4 VU로 처리 (SLO 충족).
 *   → 130rps 초과: VU 풀 포화 + dropped 발생. 실제 처리 상한선 ≈ 90~100 orders/s(002와 일치).
 *   → http_req_failed=0%: 과부하에서도 4xx/5xx 없음 (비관적 락이 느려질 뿐 에러 없음).
 *   → order_5xx=0: 주문 5xx 없음 — 락 붕괴 없음.
 *
 * 단계 설계:
 *   [0s ]  50→100rps / 30s  — 워밍업·정상 구간
 *   [30s] 100→120rps / 45s  — 점증 시작
 *   [75s] 120→140rps / 45s
 *   [2m ] 140→160rps / 45s
 *   [2m45s] 160→180rps / 45s
 *   [3m30s] 180→200rps / 45s  — 붕괴 구간 진입
 *   [4m15s] 200→0rps   / 15s  — 쿨다운
 * 총 ~4m30s (< access TTL 30분)
 *
 * thresholds: 진단용(느슨) — p95/dropped로 런을 죽이지 않는다.
 *   order_5xx==0은 유지: 과부하라도 비관적 락은 느려질 뿐 5xx는 비정상.
 *
 * 사용 (JSON 산출은 SUMMARY_EXPORT_PATH 사용 — handleSummary가 토큰 제외):
 *   k6 run -e PROFILE=stress \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e SUMMARY_EXPORT_PATH=shop-core/perf/k6/baselines/order-create-stress.json \
 *     shop-core/perf/k6/scenarios/order-create.js
 *
 * 단계별 p95/dropped 읽기:
 *   k6 stdout 주기 출력(10s 간격)으로 각 단계 관찰.
 *   aggregate summary는 단계 분리가 안 되므로, 상세 시계열은 Grafana로 확인한다.
 */

import { PROFILES, STRESS_THRESHOLDS } from '../lib/config.js';

const p = PROFILES.stress;

export const options = {
  scenarios: {
    order_create: {
      executor: 'ramping-arrival-rate',
      startRate: p.startRate,
      timeUnit: p.timeUnit,
      stages: p.stages,
      preAllocatedVUs: p.preAllocatedVUs,
      maxVUs: p.maxVUs,
    },
  },
  thresholds: STRESS_THRESHOLDS,
};
