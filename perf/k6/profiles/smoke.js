/**
 * profiles/smoke.js
 *
 * smoke 프로파일 — 스크립트 동작·thresholds 충족 여부 확인용.
 * 1~5 VU, 30초 짧게 실행. 부하 발견이 목적이 아니라 "정상 동작 확인"이 목적.
 *
 * 사용:
 *   k6 run -e PROFILE=smoke shop-core/perf/k6/scenarios/order-create.js
 *
 * 또는 시나리오 파일이 이 파일의 options를 직접 import해서 사용:
 *   import { options } from '../profiles/smoke.js';
 */

import { THRESHOLDS, PROFILES } from '../lib/config.js';

export const options = {
  vus: PROFILES.smoke.vus,
  duration: PROFILES.smoke.duration,
  thresholds: THRESHOLDS,
};
