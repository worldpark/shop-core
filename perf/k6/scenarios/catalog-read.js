/**
 * scenarios/catalog-read.js
 *
 * 공개 카탈로그 읽기 부하 시나리오 — 가상스레드 A/B 측정용 (Task 006) +
 *                                    pg_trgm 검색 베이스라인 측정용 (Task 058).
 *
 * 목적:
 *   플랫폼 스레드(기본 200) 이상의 동시성으로 공개 상품 목록/상세 GET을 가압한다.
 *   락 없는 DB 읽기 경로에서 VT vs 플랫폼 스레드의 throughput/p95/p99 차이를 측정한다.
 *   (쓰기 경로는 풀 바운드라 VT 이득이 작을 수 있음 — 읽기가 VT에 더 공정한 기회를 줌.)
 *
 *   [Task 058 추가] SEARCH_KEYWORD env가 지정된 경우, 목록 호출에 keyword 파라미터를 부착해
 *   ProductRepository의 LOWER(p.name) LIKE 검색 절(+pg_trgm GIN 인덱스 경로)을 가압한다.
 *   env 미지정(기본 빈 문자열)이면 현행과 완전히 동일하게 동작한다 — 회귀 0 보장.
 *
 * 흐름:
 *   setup()  : setupCatalogSeed() — seller 생성·상품 N개 등록+ON_SALE·variant 생성
 *   default(): GET /api/v1/products (목록, SEARCH_KEYWORD 있으면 keyword 파라미터 부착)
 *              + 랜덤 GET /api/v1/products/{id} (상세)
 *              인증 헤더 불필요 (공개 permitAll 엔드포인트)
 *   handleSummary(): setup_data(sellerToken) 제거, 메트릭 전용 JSON export
 *
 * REST 계약 (SecurityConfig 확인 완료):
 *   GET /api/v1/products               — permitAll, ?page=0&size=20&sort=latest
 *   GET /api/v1/products/{productId}   — permitAll
 *   응답: { content: [{productId, name, ...}], page, size, totalElements, totalPages }
 *
 * ★ 운영 안전:
 *   읽기 전용이므로 주문/이벤트/Outbox/Kafka 없음. notification 무관(안전).
 *   signup(seller) 1회로 환영 메일이 발생할 수 있으나 횟수가 적어 실용 무해.
 *   그래도 notification log 모드 권장 (측정 환경 표준 절차).
 *
 * 프로파일 선택:
 *   -e PROFILE=conc   (기본 closed 모델: CONC_VUS×CONC_DURATION, 300VU×40s)
 *   -e PROFILE=smoke  (동작 확인: 5VU×30s)
 *   -e PROFILE=load   (arrival-rate 60rps×1m)
 *
 * 환경변수:
 *   BASE_URL            — 기본 http://localhost:8080
 *   PROFILE             — conc (기본) | smoke | load | stress
 *   CONC_VUS            — conc 프로파일 VU 수 (기본 300). A/B 스윕 시 100/300/500.
 *   CONC_DURATION       — conc 프로파일 지속 시간 (기본 40s).
 *   ADMIN_EMAIL         — 기본 admin@example.com
 *   ADMIN_PASSWORD      — 기본 Admin1234!
 *   RUN_TAG             — 런별 유니크 prefix (미지정 시 uuidv4 자동)
 *   SUMMARY_EXPORT_PATH — 메트릭 전용 JSON 출력 경로 (sellerToken 제외)
 *   SEARCH_KEYWORD      — [Task 058] 검색 가압용 keyword (기본 빈 문자열 = 검색 없음).
 *                         값이 있으면 목록 URL에 &keyword=<encodeURIComponent 값>을 부착한다.
 *                         시드 상품명이 'PERF-Catalog-<prefix>-...' 형태이므로
 *                         keyword=Catalog (≥3그램, 유효 부분문자열)이 표준 가압값이다.
 *                         단, 'Catalog'는 전 시드 상품에 매치돼 selectivity가 낮으므로
 *                         (거의 전건 매치) 인덱스 이득이 작게 보일 수 있다 — README §15 참조.
 *
 * 커스텀 메트릭:
 *   read_duration (Trend)   — GET 요청 지연 (목록+상세 통합). p95/p99 비교 주 지표.
 *   read_5xx      (Counter) — >=500. threshold: count==0. 공개 읽기 5xx는 서버 오류.
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

import { PROFILES, READ_THRESHOLDS, BASE_URL, SEED } from '../lib/config.js';
import { setupCatalogSeed } from '../lib/seed.js';

// ---------------------------------------------------------------
// 프로파일 결정
// catalog-read의 기본 프로파일은 conc (측정 목적 시나리오).
// ---------------------------------------------------------------
const PROFILE = __ENV.PROFILE || 'conc';

// ---------------------------------------------------------------
// [Task 058] 검색 keyword 파라미터화
// 값이 있으면 목록 URL에 &keyword=<encodeURIComponent 값> 부착.
// 없으면(기본 빈 문자열) 현행과 완전히 동일하게 동작 — 회귀 0 보장.
//
// 표준 가압 예: SEARCH_KEYWORD=Catalog
//   시드 상품명 'PERF-Catalog-<prefix>-...'의 유효 부분문자열(≥3그램).
//   pg_trgm GIN 인덱스(V12 idx_products_name_trgm)가 LOWER(p.name) LIKE
//   LOWER(CONCAT('%', kw, '%')) 좌변과 매칭돼 Bitmap Index Scan을 탄다.
// ---------------------------------------------------------------
const SEARCH_KEYWORD = __ENV.SEARCH_KEYWORD || '';

const p = PROFILES[PROFILE];
if (!p) {
  throw new Error(
    `[catalog-read] 알 수 없는 PROFILE="${PROFILE}". 허용값: ${Object.keys(PROFILES).join('|')}`,
  );
}

// ---------------------------------------------------------------
// k6 options — closed(vus+duration) / open(arrival-rate) / ramping 분기
// catalog-read는 주로 closed(conc/smoke) 으로 구동하나 load/stress도 지원.
// ---------------------------------------------------------------
const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'];

function buildOptions(profile) {
  // catalog-read는 항상 READ_THRESHOLDS를 사용한다.
  // PROFILES의 thresholds(smoke→SMOKE_THRESHOLDS 등)는 order_5xx 같은 쓰기 전용 메트릭을
  // 포함하므로 이 시나리오에 그대로 적용하면 k6가 "metric not found" 오류를 낸다.
  // 따라서 profile.thresholds 대신 READ_THRESHOLDS를 고정 적용한다.
  const thresholds = READ_THRESHOLDS;

  if (profile.kind === 'ramping-arrival-rate') {
    return {
      summaryTrendStats: SUMMARY_TREND_STATS,
      scenarios: {
        catalog_read: {
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
        catalog_read: {
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
  // closed 모델 (smoke / conc)
  return {
    summaryTrendStats: SUMMARY_TREND_STATS,
    vus: profile.vus,
    duration: profile.duration,
    thresholds,
  };
}

export const options = buildOptions(p);

// ---------------------------------------------------------------
// 커스텀 메트릭
// ---------------------------------------------------------------
/** GET 요청 지연 (목록·상세 통합). A/B 비교 주 지표 — p95/p99. */
const readDuration = new Trend('read_duration', true);
/** >=500 카운트. threshold: count==0. 공개 읽기 5xx는 서버 오류. */
const read5xx = new Counter('read_5xx');

// ---------------------------------------------------------------
// setup() — 런 1회, 카탈로그 시드
// ---------------------------------------------------------------
export function setup() {
  // 시드 상품 수: CATALOG_PRODUCT_COUNT (기본 50)
  // conc/stress처럼 VU가 많아도 상품 읽기는 공유(동일 productIds 재사용)이므로
  // buyerCount 기반 비례 증가 불필요. 50개면 랜덤 상세 접근에 충분한 다양성 제공.
  const seed = setupCatalogSeed(SEED.CATALOG_PRODUCT_COUNT);

  if (!seed.productIds || seed.productIds.length === 0) {
    throw new Error('[catalog-read] setup: 시드 상품 없음 — setupCatalogSeed 실패');
  }

  return { productIds: seed.productIds };
}

// ---------------------------------------------------------------
// default() — VU 본문
// 인증 헤더 불필요. 공개 permitAll 엔드포인트.
// ---------------------------------------------------------------
export default function (data) {
  const productIds = data.productIds;

  // ---- 1. 목록 조회 (GET /api/v1/products) -----------------------
  // [Task 058] SEARCH_KEYWORD env가 지정된 경우 keyword 파라미터를 부착한다.
  // 미지정(기본 빈 문자열)이면 현행과 동일한 URL — 회귀 0.
  const keywordParam = SEARCH_KEYWORD ? `&keyword=${encodeURIComponent(SEARCH_KEYWORD)}` : '';
  const listStart = Date.now();
  const listRes = http.get(
    `${BASE_URL}/api/v1/products?page=0&size=20&sort=latest${keywordParam}`,
  );
  const listDuration = Date.now() - listStart;
  readDuration.add(listDuration);

  const listOk = check(listRes, {
    'list 200': (r) => r.status === 200,
  });

  if (!listOk) {
    if (listRes.status >= 500) {
      read5xx.add(1);
    }
    // 목록 실패 시 상세 건너뜀
    return;
  }

  // ---- 2. 상세 조회 (GET /api/v1/products/{id}) ------------------
  // setup이 시드한 productId 중 VU별·이터레이션별 랜덤 선택 (목록에서 추출하지 않아도 됨).
  // __VU(1-based)와 __ITER(0-based)를 조합해 균등 분산 접근.
  const idx = (__VU + __ITER) % productIds.length;
  const productId = productIds[idx];

  const detailStart = Date.now();
  const detailRes = http.get(
    `${BASE_URL}/api/v1/products/${productId}`,
  );
  const detailDuration = Date.now() - detailStart;
  readDuration.add(detailDuration);

  const detailOk = check(detailRes, {
    'detail 200': (r) => r.status === 200,
  });

  if (!detailOk) {
    if (detailRes.status >= 500) {
      read5xx.add(1);
    }
  }
}

// ---------------------------------------------------------------
// handleSummary() — sellerToken 제외, 메트릭 전용 JSON export
//
// k6의 setup() 반환값(sellerToken 포함)이 setup_data에 노출되지 않도록
// handleSummary에서 setup_data를 제거한 메트릭 전용 JSON을 내보낸다.
//
// 사용:
//   k6 run -e SUMMARY_EXPORT_PATH=build/k6/catalog-read-conc.json ...
// ---------------------------------------------------------------
export function handleSummary(data) {
  const safeData = {
    root_group: data.root_group,
    metrics: data.metrics,
    // setup_data는 의도적으로 제외 (sellerToken 노출 방지)
  };

  const outputs = {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };

  const exportPath = __ENV.SUMMARY_EXPORT_PATH;
  if (exportPath) {
    outputs[exportPath] = JSON.stringify(safeData, null, 2);
  }

  return outputs;
}
