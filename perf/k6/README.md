# shop-core k6 부하 테스트 하니스

> 대상 Task: `docs/tasks/performance/001-performance-shop-core-k6-order-create-smoke-baseline.md` (Task 001)
>           `docs/tasks/performance/002-performance-shop-core-k6-order-create-load-profile.md` (Task 002)
>           `docs/tasks/performance/003-performance-shop-core-k6-order-create-stress-profile.md` (Task 003)
>           `docs/tasks/performance/004-performance-shop-core-k6-payment-confirm-scenario.md` (Task 004)
>           `docs/tasks/performance/005-performance-shop-core-k6-coupon-apply-scenario.md` (Task 005)
>           `docs/tasks/performance/006-performance-shop-core-virtual-thread-ab-evaluation.md` (Task 006)
> Plan: `docs/plans/performance/001-shop-core-k6-order-create-smoke-baseline-plan.md`
>       `docs/plans/performance/002-shop-core-k6-order-create-load-profile-plan.md`
>       `docs/plans/performance/003-shop-core-k6-order-create-stress-profile-plan.md`
>       `docs/plans/performance/004-shop-core-k6-payment-confirm-scenario-plan.md`
>       `docs/plans/performance/005-shop-core-k6-coupon-apply-scenario-plan.md`
>       `docs/plans/performance/006-shop-core-virtual-thread-ab-evaluation-plan.md`

## 디렉토리 구조

```
shop-core/perf/k6/
  lib/
    config.js        # BASE_URL·PROFILE·thresholds(smoke/load/stress/coupon/read 분리)·시드 상수 (공통)
    auth.js          # 로그인·회원가입·Bearer 헤더 헬퍼·토큰 갱신(getValidToken)
    seed.js          # setup() 전용 — seller/상품/variant/buyer 시드 + setupCouponSeed + setupCatalogSeed
  scenarios/
    order-create.js   # 핫패스: cart add → order create + 커스텀 메트릭 (smoke/load/stress 분기)
    payment-confirm.js # 종단: cart→order→결제 확정(빈 바디) + payment_confirm_duration (Task 004)
    coupon-apply.js   # 쿠폰 사용 동시성: cart→쿠폰적용 주문 + 충돌(409) 직렬화 검증 (Task 005)
    catalog-read.js   # 공개 읽기 가압 (VT A/B 측정 — Task 006): GET /products + GET /products/{id}
  profiles/
    smoke.js         # 5 VU × 30s options export (closed 모델)
    load.js          # 60 rps × 1m options export (open: constant-arrival-rate, 포화점 아래)
    stress.js        # 50→200 rps ramping options export (open: ramping-arrival-rate) — Task 003
  baselines/
    order-create-smoke.json    # smoke 베이스라인 (Task 001, 2026-06-16)
    order-create-load.json     # load 베이스라인 (Task 002, 2026-06-16)
    order-create-stress.json   # stress 베이스라인 (Task 003, 2026-06-16)
    payment-confirm-load.json  # payment-confirm load 베이스라인 (Task 004, 2026-06-16)
    coupon-apply-load.json     # coupon-apply load 베이스라인 (Task 005, 측정 후 추가)
  README.md          # 이 문서
```

산출 JSON:
- **런타임 export**: `build/k6/order-create-{smoke,load}.json` (`--summary-export`로 생성). `build/`는 gitignore 대상 — 일회성·비커밋.
- **커밋된 베이스라인(추세 비교 기준)**: `shop-core/perf/k6/baselines/order-create-{smoke,load,stress}.json`. 새 기준을 세울 때만 의도적으로 갱신·커밋한다.

---

## 0. 사전 점검

앱이 기동 중인지 확인한다.

```bash
# actuator가 노출된 경우
curl -s http://localhost:8080/actuator/health

# 또는 로그인 엔드포인트 도달성 확인
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"ping","password":"ping"}'
# 400 또는 401이면 앱이 응답 중 — 정상
```

**Kafka가 중단된 상태**이면 주문 생성(`POST /api/v1/orders`)이 Outbox 이벤트 발행 단계에서 실패하거나 타임아웃이 발생할 수 있다. 반드시 인프라 스택(PG + Redis + Kafka)을 모두 기동한 후 k6를 실행한다.

---

## 1. 인프라 스택 기동

docker-compose는 **인프라 전용** (PostgreSQL + Redis + Kafka)이다. **앱(shop-core)은 컨테이너화되어 있지 않으므로 별도로 기동해야 한다.**

```bash
# 인프라만 기동 (앱 컨테이너 없음)
docker compose -f docker/shop/docker-compose.yml up -d
```

기동 서비스: `shop-core-postgres` (5432), `shop-kafka` (9092), `shop-redis` (6379), `shop-kafka-ui` (8085), `shop-notification-postgres` (5433)

---

## 2. 앱 기동 (별도)

```bash
# Gradle (shop-core 디렉토리에서)
cd shop-core
./gradlew bootRun

# 또는 IDE에서 ShopApplication 클래스 직접 실행
# 기본 포트: localhost:8080
```

compose가 앱을 띄우지 않는다. 앱은 반드시 별도로 기동해야 한다.

---

## 3. admin 계정 부트스트랩 (fresh perf DB 1회)

k6 seed는 `admin@example.com` 계정으로 로그인해 seller를 승격한다. 처음 실행하거나 DB를 초기화한 경우 아래 커맨드로 admin 계정을 생성한다.

```bash
# shop-core 디렉토리에서
cd shop-core
./gradlew test --tests "*AdminAccountSeedTest*" -Dseed.admin.enabled=true
```

- 계정: `admin@example.com` / `Admin1234!` (role=ADMIN)
- 이미 존재하면 upsert (재실행 안전)
- `__ENV.ADMIN_EMAIL` / `__ENV.ADMIN_PASSWORD` 환경변수로 오버라이드 가능

---

## 4. smoke 실행

```bash
# Windows (k6 전체 경로)
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run ^
  -e PROFILE=smoke ^
  -e BASE_URL=http://localhost:8080 ^
  -e SUMMARY_EXPORT_PATH=build/k6/order-create-smoke.json ^
  shop-core/perf/k6/scenarios/order-create.js

# macOS/Linux (k6 PATH 등록된 경우)
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=smoke \
  -e SUMMARY_EXPORT_PATH=build/k6/order-create-smoke.json \
  shop-core/perf/k6/scenarios/order-create.js
```

> **참고**: `--summary-export` 대신 `-e SUMMARY_EXPORT_PATH` 를 사용한다. `handleSummary` 구현으로 setup_data(JWT 토큰)가 제거된 메트릭 전용 JSON을 내보낸다.

`build/k6/` 디렉토리가 없으면 미리 생성한다.

### Docker 대안 (k6 로컬 미설치 시)

```bash
# Linux/macOS
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e PROFILE=smoke \
  -v "$(pwd)/shop-core/perf/k6:/scripts" \
  grafana/k6 run /scripts/scenarios/order-create.js

# Windows PowerShell
docker run --rm -i `
  -e BASE_URL=http://host.docker.internal:8080 `
  -e PROFILE=smoke `
  -v "${PWD}/shop-core/perf/k6:/scripts" `
  grafana/k6 run /scripts/scenarios/order-create.js
```

컨테이너 → 호스트 앱 접속 시 `host.docker.internal` 을 사용한다 (`localhost` 는 컨테이너 내부를 가리킴).

---

## 5. load 실행 (Task 002 추가)

```bash
# Windows (k6 전체 경로)
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run ^
  -e PROFILE=load ^
  -e BASE_URL=http://localhost:8080 ^
  -e SUMMARY_EXPORT_PATH=build/k6/order-create-load.json ^
  shop-core/perf/k6/scenarios/order-create.js

# macOS/Linux
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=load \
  -e SUMMARY_EXPORT_PATH=build/k6/order-create-load.json \
  shop-core/perf/k6/scenarios/order-create.js
```

> **참고**: `--summary-export` 대신 `-e SUMMARY_EXPORT_PATH` 를 사용한다. `handleSummary` 구현으로 setup_data(JWT 토큰)가 제거된 메트릭 전용 JSON을 내보낸다.

### load 프로파일 설계

- **executor**: `constant-arrival-rate` (open 모델) — 응답 시간과 무관하게 목표 RPS를 고정 가압.
- **목표**: **60 rps × 1분** (포화점 아래 — 지속가능 운영수준). 2026-06-16 확정(100→60 하향, 아래 근거).
- **VU 풀**: preAllocatedVUs=15, maxVUs=30. 60rps는 VU ~5개로 충분(에스컬레이션 없음).
- **dropped_iterations**: VU 풀 한계 도달 시 발생. 과도한 dropped는 `maxVUs` 상향 또는 목표 RPS 하향 신호.

### 목표 RPS 확정 근거 (2026-06-16) — 왜 100→60으로 낮췄나

앱 처리 상한 ≈ **90~100 orders/s** (단일 variant `PESSIMISTIC_WRITE` 직렬화). **100rps는 이 상한과 거의 같아 "포화점 측정"**이라, open 모델이 VU를 maxVUs까지 과투입 → 자기유발 락 경합 → **p95가 런마다 18~143ms로 flaky**했다. load는 "지속가능 운영수준"을 안정적으로 봐야 하므로 목표를 포화점·knee(120) 아래인 **60rps**로 낮춘다.

| RPS | p95 | p99 | dropped/s | VU | 판정 |
|---|---|---|---|---|---|
| **60** | **22ms** | **35~54ms** | **0** | **~5** | **SLO 충족·안정 (확정 목표)** |
| 100 | 18~143ms(변동) | 33~283ms | 0 | ~15~50 | 포화점 — flaky, load 부적합 |
| 200 | 634ms | — | 71.9 | 200 | 한계 초과 (stress 영역) |
| 300 | 886ms | — | 138.5 | 200 | 완전 한계 초과 |

- 60rps에서 VU가 ~5개로 안정(에스컬레이션 없음) → p95/p99 변동이 작아 회귀 감지 임계가 의미 있다.
- 누적 데이터는 성능을 심각히 떨어뜨리므로(orders 41,531개 시 p95=242ms, 13배) 베이스라인은 반드시 **깨끗한 DB**에서 측정한다.
- 한계·붕괴점(knee≈120rps) 탐색은 **stress(Task 003)** 가 담당 — load와 역할 분리.

---

## 5-2. stress 실행 (Task 003 추가)

```bash
# Windows (k6 전체 경로)
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=stress `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=shop-core/perf/k6/baselines/order-create-stress.json `
  shop-core/perf/k6/scenarios/order-create.js

# macOS/Linux
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=stress \
  -e SUMMARY_EXPORT_PATH=shop-core/perf/k6/baselines/order-create-stress.json \
  shop-core/perf/k6/scenarios/order-create.js
```

### stress 프로파일 설계

- **executor**: `ramping-arrival-rate` (open 모델) — 단계별 목표 RPS를 자동 점증.
- **단계**: 50rps 시작 → 100→120→140→160→180→200rps (각 45s) → 0 쿨다운 (총 약 4분 30초).
- **VU 풀**: preAllocatedVUs=50, maxVUs=200. 응답 지연이 쌓여도 VU를 최대 200까지 투입.
- **선행 탐색(002) 근거**: 100rps(p95=18ms, dropped=0) 정상 / 200rps(p95=634ms, dropped=71/s) 붕괴. knee는 100~200rps 사이로 추정.

### 붕괴점(knee) 해석

**aggregate summary는 모든 단계 합산**이므로 단계별 분리가 안 된다. 단계별 p95/dropped는 두 가지 방법으로 읽는다.

1. **k6 stdout 주기 출력** (10초 간격): 실행 중 터미널에 출력되는 진행률 라인(iters/s·VU 수)을 시간대별로 관찰한다. VU 수가 갑자기 폭증하고 `Insufficient VUs` 경고가 나타나는 시점이 포화 시작이다.
2. **Grafana 시계열** (관측성 도입 시): hikaricp·http_server_requests p95 등 서버측 지표와 함께 볼 수 있다.

knee 판정 기준:
- `dropped_iterations`가 눈에 띄게 증가하기 시작하는 단계의 직전 RPS = SLO 마지막 유지 RPS.
- stdout VU 수가 3~5개에서 갑자기 수십~200개로 폭증하는 시점 = VU 풀 포화 = 처리 한계 도달.

**2026-06-16 Task 003 실측 (깨끗한 DB, baselines/order-create-stress.json)**:

| 단계 | 시간대 | iters/s | 활성 VU | 관찰 |
|---|---|---|---|---|
| 50→100rps (워밍업) | 0~30s | ~50→100 | 1~4 | 정상, SLO 충족 |
| 100→120rps | 30s~1m30s | ~100→115 | 3~13 | 안정 처리 |
| ~130rps 초과 | ~1m30s | ~115 | 13→197→200 | **VU 풀 포화** + `Insufficient VUs` 경고 |
| 140~200rps | 1m30s~4m30s | ~130→192 | 200 (고정) | dropped 누적 (총 8442회, 28.5/s) |
| 쿨다운 | ~4m42s | 192→138 급감 | 200→0 | 정상 감소 |

**확정 knee: 약 120rps** — 이 RPS 이하에서는 dropped 없이 3~4 VU로 처리. 130rps 초과 시 VU 풀 포화 + dropped 발생.

aggregate 요약: p95=1.1s / p99=1.4s / http_req_failed=0.00% / order_5xx=0 / dropped=8442(28.5/s).

> Grafana로 서버측(hikaricp 커넥션 포화·http_server_requests p95)도 함께 볼 수 있다.

### stress thresholds

stress는 "어디서 무너지나"를 측정하는 것이 목적이므로 p95/dropped_iterations로 런을 죽이지 않는다.

| metric | 값 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 과부하 타임아웃 시 위반 가능 — 곡선의 일부로 기록 |
| `order_5xx` | `count==0` | 비관적 락은 느려질 뿐 5xx는 비정상 붕괴 |
| `http_req_duration` | 임계 없음 | 측정 대상 — stdout/Grafana로 곡선 관찰 |
| `dropped_iterations` | 임계 없음 | 측정 대상 — knee 식별 지표 |

---

## 5-3. 토큰 갱신(TOKEN_REFRESH_AFTER_SEC)

stress는 약 4분 30초 런으로 JWT access TTL(30분) 미만이므로 기본 설정(1500초=25분)에서는 토큰 갱신이 발화하지 않는다.

토큰 갱신 기능 자체를 검증하려면 임계를 줄여 강제 발화한다:

```bash
# 토큰 갱신 기능 검증 — TOKEN_REFRESH_AFTER_SEC=5로 짧게 발화
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=smoke `
  -e BASE_URL=http://localhost:8080 `
  -e TOKEN_REFRESH_AFTER_SEC=5 `
  shop-core/perf/k6/scenarios/order-create.js
# → 5초마다 POST /api/v1/auth/refresh 호출, 401 없이 통과해야 함
```

갱신 흐름:
1. VU 최초 이터레이션: `setup()`에서 받은 accessToken + refreshToken을 VU-로컬 캐시에 저장.
2. 이후 이터레이션: 토큰 나이(현재 시각 - issuedAt) > `TOKEN_REFRESH_AFTER_SEC`이면 `POST /api/v1/auth/refresh {refreshToken}`으로 새 accessToken 획득·캐시 교체.
3. smoke/load(기본 임계 1500초): 짧은 런에서는 임계에 도달하지 않아 갱신이 발화하지 않는다 — 기존 동작 동일(회귀 없음).

---

## 6. 환경변수

| 변수명 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 가압 대상 앱 루트 |
| `PROFILE` | `smoke` | 부하 프로파일 (`smoke` \| `load` \| `stress`) |
| `ADMIN_EMAIL` | `admin@example.com` | admin 계정 이메일 |
| `ADMIN_PASSWORD` | `Admin1234!` | admin 계정 비밀번호 |
| `RUN_TAG` | uuidv4 자동 생성 | 런별 유니크 prefix (데이터 네임스페이스) |
| `TOKEN_REFRESH_AFTER_SEC` | `1500` | 토큰 갱신 발화 임계(초). 기본 25분(30분 TTL - 5분 버퍼). 갱신 기능 검증 시 `5` 등으로 지정. |

---

## 7. thresholds 확정 절차

### smoke thresholds (SMOKE_THRESHOLDS)

베이스라인(2026-06-16 확정):

| metric | 값 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 에러율 1% 미만 |
| `http_req_duration p95` | `p(95)<100` | 베이스라인 p95≈40ms × ~2.5 여유 |
| `http_req_duration p99` | `p(99)<200` | 베이스라인 p99≈53ms × ~3.8 여유 |
| `order_5xx` | `count==0` | 락 붕괴=비정상, 0이어야 함 |

### load thresholds (LOAD_THRESHOLDS)

베이스라인(2026-06-16 재확정, 깨끗한 DB 기준, **60rps×1m** — 포화점 아래로 하향해 flaky 제거):

| metric | 값 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 에러율 1% 미만 |
| `http_req_duration p95` | `p(95)<60` | 깨끗한 DB 60rps p95≈22ms × ~2.5 여유 → 60ms |
| `http_req_duration p99` | `p(99)<150` | 깨끗한 DB 60rps p99≈54ms × ~2.5 여유 → 150ms (포화점 아래라 변동 작음) |
| `order_5xx` | `count==0` | 락 붕괴=비정상, 0이어야 함 |
| `dropped_iterations` | `rate<3` | 깨끗한 DB 실측 0/s 허용, 실질 under-provision(71~138/s) 차단 |

### stress thresholds (STRESS_THRESHOLDS)

베이스라인(2026-06-16, 깨끗한 DB 기준, ramping-arrival-rate 50→200rps):

| metric | 값 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 과부하 타임아웃 시 위반 가능 — 곡선의 일부로 기록 |
| `order_5xx` | `count==0` | 락 붕괴=비정상, 0이어야 함 |
| `http_req_duration p95` | 임계 없음 | 붕괴 곡선 측정이 목적 |
| `dropped_iterations` | 임계 없음 | knee 식별 지표 |

### thresholds 갱신 절차

1. 목표 프로파일(`-e PROFILE=smoke` 또는 `load`)으로 k6 실행
2. summary에서 `http_req_duration` p95/p99, `http_req_failed`, `dropped_iterations` rate 확인
3. `lib/config.js`의 대응 `SMOKE_THRESHOLDS` / `LOAD_THRESHOLDS` 값을 관측값 × 여유율로 교체
4. 런타임 export(`build/k6/...`)를 커밋된 베이스라인 `baselines/order-create-{smoke,load}.json`으로 복사

---

## 8. 커스텀 메트릭

| 메트릭 | 종류 | 설명 |
|---|---|---|
| `order_created` | Counter | 주문 생성 성공(201) 누적 수 |
| `order_conflict` | Counter | 409 (낙관 충돌·재고 부족) — 가시화용, threshold 없음 |
| `order_5xx` | Counter | 500 이상 — threshold: count==0 |
| `order_create_duration` | Trend | 주문 POST 자체 지연 (ms) |

---

## 9. 데이터 오염 방지

- 런마다 `RUN_TAG`(또는 uuidv4) prefix로 seller/상품/buyer 이메일·SKU를 네임스페이스 분리한다.
- perf 전용 DB(별도 `shop_core_perf` 데이터베이스)를 권장한다.
- 시드 데이터 self-clean은 후속 Task에서 teardown()으로 구현 예정이다.
- **누적 실행 시 응답 지연 증가**: 런을 반복 실행할수록 DB 데이터가 누적되어 쿼리 성능이 하락할 수 있다. 정확한 베이스라인 측정이 필요하면 perf DB를 초기화한 후 실행한다.

---

## 10. Kafka 오프 시 충실도 저하 경고

Kafka가 중단된 상태에서 실행하면:
- 주문 생성 후 Outbox 이벤트 발행이 실패하거나 타임아웃이 발생할 수 있다.
- `order_5xx` counter가 증가하고 `http_req_failed` threshold가 위반될 수 있다.
- 이 경우 성능 수치가 "정상 운영" 기준이 아니므로 베이스라인으로 사용하지 않는다.

반드시 `docker compose -f docker/shop/docker-compose.yml up -d` 로 인프라를 완전히 기동한 후 실행한다.

---

## 11. payment-confirm 시나리오 (Task 004)

> Plan: `docs/plans/performance/004-shop-core-k6-payment-confirm-scenario-plan.md`
>
> 결제 확정 핫패스(`POST /api/v1/orders/{orderId}/payment`) — cart add → order create → 결제 확정.
> Outbox 발행(OrderCompletedEvent → order-completed 토픽) 포함.

### ★ 운영 안전 게이트 (실행 전 필수)

> **notification이 smtp 모드인 채로 실행하면 실 Gmail 주문확정 메일이 대량 발송된다.**
> 반드시 아래 조건을 확인한 후 실행할 것.

| 항목 | 조건 | 확인 방법 |
|---|---|---|
| **notification 안전** | log 모드이거나 프로세스 정지 | notification 로그 확인 또는 `MAIL_MODE=log` 환경 확인 |
| **Kafka up** | Outbox → order-completed 발행 필수 | `docker compose ps` → kafka healthy |
| **깨끗한 DB** | 베이스라인 측정 전 TRUNCATE 권장 | 메인이 perf DB TRUNCATE 후 실행 |

Kafka가 중단된 상태에서 실행하면 결제 확정 후 Outbox 이벤트 발행이 실패하거나 타임아웃이 발생해 `payment_5xx`가 증가한다. 이 경우 성능 수치가 "정상 운영" 기준이 아니므로 베이스라인으로 사용하지 않는다.

### 시나리오 설계 (방식 (a) — 종단 측정)

각 VU 반복 = `cart add → order create → 결제 확정`으로 구성한다. 매 반복 신선한 PENDING 주문을 만들어 결제하므로 풀 소진 관리가 없다(자기 replenish).

**한계 정직 표기**: 이 시나리오는 order-create 단계(단일 variant `PESSIMISTIC_WRITE`)를 거치므로 **"종단(order→pay) throughput"** 측정이다. 순수 결제 고립이 아니며 상한은 order-create(~90~100/s)와 동일하다. 결제 단계 자체 지연은 `payment_confirm_duration Trend`로 분리 계측한다. 순수 결제 고립(풀 시드 방식 (b))은 후속 옵션.

### REST 계약 (plan §2 확정)

| 단계 | 메서드·경로 | 요청 바디 | 응답 | status |
|---|---|---|---|---|
| 장바구니 담기 | `POST /api/v1/cart/items` | `{variantId, quantity:1}` | — | 200 |
| 주문 생성 | `POST /api/v1/orders` | 수령인·주소 | `{orderId, ...}` | 201 |
| **결제 확정** | `POST /api/v1/orders/{orderId}/payment` | `{}` (빈 바디) | `{status:"paid", paymentId, ...}` | **200** |

**amount는 전송하지 않는다.** finalAmount와 불일치 시 400이 발생하므로, 서버 기본값(method="mock", amount=null → 주문 finalAmount 사용)을 사용한다. orderId는 order create 응답(201)에서 추출한다.

### smoke 실행

```bash
# Windows (k6 전체 경로) — 운영 안전 게이트 확인 후 실행
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=smoke `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/payment-confirm-smoke.json `
  shop-core/perf/k6/scenarios/payment-confirm.js

# macOS/Linux
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=smoke \
  -e SUMMARY_EXPORT_PATH=build/k6/payment-confirm-smoke.json \
  shop-core/perf/k6/scenarios/payment-confirm.js
```

### load 실행 (베이스라인 측정)

```bash
# Windows
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=load `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/payment-confirm-load.json `
  shop-core/perf/k6/scenarios/payment-confirm.js

# macOS/Linux
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=load \
  -e SUMMARY_EXPORT_PATH=build/k6/payment-confirm-load.json \
  shop-core/perf/k6/scenarios/payment-confirm.js
```

> **참고**: `--summary-export` 대신 `-e SUMMARY_EXPORT_PATH`를 사용한다. `handleSummary` 구현으로 setup_data(JWT 토큰)가 제거된 메트릭 전용 JSON을 내보낸다.

### stress 실행 (한계 탐색)

```bash
# Windows
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=stress `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=shop-core/perf/k6/baselines/payment-confirm-stress.json `
  shop-core/perf/k6/scenarios/payment-confirm.js
```

### PAYMENT_THRESHOLDS 확정 (2026-06-16 완료)

`PAYMENT_THRESHOLDS.payment_confirm_duration`은 **실측 확정**됐다(운영 안전 게이트: notification 정지 + Kafka up + 깨끗한 DB, load 60rps×1m 2회).
- 관측(2회 일관): payment_confirm_duration p95=18ms, p99=23~24ms, payment_5xx=0, http_req_failed=0%, ~56 결제/s.
- 확정 임계: `p(95)<50`(18×~2.7), `p(99)<80`(24×~3.3). 산출물 `baselines/payment-confirm-load.json`(토큰 제외, p99 포함).
- 결제 POST가 빠른 이유: Outbox 이벤트 외부화는 **커밋 후 비동기**라 POST 응답에 포함 안 됨(행 락+payment INSERT만). 서로 다른 주문을 결제하므로 결제 행 락은 경합 없음.
- 재측정 절차: 안전 게이트 확인 → 깨끗한 DB → `-e PROFILE=load -e SUMMARY_EXPORT_PATH=...payment-confirm-load.json` → 관측×~2.5~3로 갱신.
- 포화점 측정 금지(002 교훈): 종단 경로 상한이 order-create와 같은 ~90~100/s이므로 load=60rps(포화점 아래)에서 측정.

### 커스텀 메트릭

| 메트릭 | 종류 | 설명 |
|---|---|---|
| `payment_confirm_duration` | Trend | 결제 POST 자체 지연 (ms) — 결제 단계만 분리 계측 |
| `payment_confirmed` | Counter | 200 & status=="paid" 성공 카운트 |
| `payment_conflict` | Counter | 409 (상태 충돌) — 가시화용, threshold 없음 |
| `payment_5xx` | Counter | >=500 — threshold: count==0 |

### PAYMENT_THRESHOLDS

| metric | 값 | 상태 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 확정 |
| `payment_5xx` | `count==0` | 확정 |
| `payment_confirm_duration p95` | `p(95)<50` | 확정 — 베이스라인 p95≈18ms × ~2.7 |
| `payment_confirm_duration p99` | `p(99)<80` | 확정 — 베이스라인 p99≈24ms × ~3.3 |
| `payment_conflict` | 임계 없음 | 가시화만 (정상 비즈니스 흐름) |

---

---

## 12. coupon-apply 시나리오 (Task 005)

> Plan: `docs/plans/performance/005-shop-core-k6-coupon-apply-scenario-plan.md`
>
> 쿠폰 사용 동시성(중복 사용 방지) 경로 가압 — cart add → 쿠폰 적용 주문 생성.
> 단일사용 직렬화(`markUsedIfUnused` 조건부 UPDATE)가 경합에서 올바르게 직렬화되는가(붕괴 아님)를 블랙박스로 확인한다.

### ★ 운영 안전 게이트 (실행 전 필수)

> **notification이 smtp 모드인 채로 실행하면 실 Gmail 메일이 대량 발송된다.**
> 시드 signup(buyer) → 환영 메일, PENDING 주문 만료 자동취소 → 취소 메일이 발생한다.
> 반드시 아래 조건을 확인한 후 실행할 것.

| 항목 | 조건 | 확인 방법 |
|---|---|---|
| **notification 안전** | log 모드이거나 프로세스 정지 | notification 로그 확인 또는 `MAIL_MODE=log` 환경 확인 |
| **Kafka up** | Outbox → 이벤트 발행 필수 | `docker compose ps` → kafka healthy |
| **깨끗한 DB** | 베이스라인 측정 전 TRUNCATE 권장 | 메인이 perf DB TRUNCATE 후 실행 |

### 시나리오 설계

**소비성 전제**: setup에서 buyer마다 쿠폰을 1회 발급(1인1매). VU 본문에서 같은 buyer의 쿠폰을 반복 사용 시도한다.
- 첫 사용 → 201 + discountAmount>0 → `coupon_applied++`
- 이후(이미 사용) → 409 → `coupon_conflict++` (정상 — 중복 사용 차단됨)

**이중사용 0 신호**: `coupon_applied` 총합이 `buyerCount`를 넘지 않으면 이중 차감 없음(블랙박스 no-double-spend).

**409 오탐 방지**: `http.setResponseCallback(http.expectedStatuses(200, 201, 409))` 적용 → 409가 `http_req_failed`에서 제외됨.

### REST 계약 (plan §2)

| 단계 | 메서드·경로 | 요청 바디 | 응답 | status |
|---|---|---|---|---|
| 쿠폰 생성(시드) | `POST /api/v1/admin/coupons` | `{code, name, discountType, value, minOrderAmount, startsAt, endsAt, usageLimit:null, isActive:true}` | `{...}` | 201 |
| 쿠폰 발급(시드) | `POST /api/v1/coupons` | `{code}` | `{userCouponId, ...}` | 201 |
| 쿠폰 적용 주문 | `POST /api/v1/orders` | `{recipient, phone, postcode, address1, userCouponId}` | `{orderId, discountAmount, ...}` | 201 (성공) / 409 (충돌) |

### smoke 실행

```bash
# Windows (k6 전체 경로) — 운영 안전 게이트 확인 후 실행
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=smoke `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/coupon-apply-smoke.json `
  shop-core/perf/k6/scenarios/coupon-apply.js

# macOS/Linux
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=smoke \
  -e SUMMARY_EXPORT_PATH=build/k6/coupon-apply-smoke.json \
  shop-core/perf/k6/scenarios/coupon-apply.js
```

### load 실행 (베이스라인 측정)

```bash
# Windows
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=load `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/coupon-apply-load.json `
  shop-core/perf/k6/scenarios/coupon-apply.js

# macOS/Linux
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=load \
  -e SUMMARY_EXPORT_PATH=build/k6/coupon-apply-load.json \
  shop-core/perf/k6/scenarios/coupon-apply.js
```

> **참고**: `--summary-export` 대신 `-e SUMMARY_EXPORT_PATH`를 사용한다. `handleSummary` 구현으로 setup_data(JWT 토큰·userCouponId)가 제거된 메트릭 전용 JSON을 내보낸다.
>
> 베이스라인 확정 후 `baselines/coupon-apply-load.json`으로 복사해 커밋한다.

### stress 실행 (한계 탐색)

```bash
# Windows
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=stress `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=shop-core/perf/k6/baselines/coupon-apply-stress.json `
  shop-core/perf/k6/scenarios/coupon-apply.js
```

### 소비성/충돌 결과 해석

| 메트릭 | 정상 해석 | 비정상 |
|---|---|---|
| `coupon_applied` | 총합 <= buyerCount (각 쿠폰 정확히 1회). buyerCount 초과 시 이중사용 → 즉각 조사 | `coupon_applied > buyerCount` |
| `coupon_conflict` | 409 카운트 증가 = 중복 사용 차단 정상 동작. 임계 없음 — 가시화만 | 없음 (가시화 전용) |
| `coupon_5xx` | 반드시 0 (직렬화 붕괴=비정상, threshold: count==0) | > 0 이면 락/제약 붕괴 즉각 조사 |
| `http_req_failed` | 409 제외 후 ~0% (진짜 오류만). rate<0.01 threshold | > 0.01 이면 진짜 에러 |
| `coupon_order_duration` | 확정 p95<60/p99<300 (409 다수 + 성공경로 소수 꼬리 혼합) | 임계 초과 |

### COUPON_THRESHOLDS (2026-06-16 확정)

> 운영 안전 게이트(notification 정지) + Kafka up + 깨끗한 DB 조건에서 load 60rps×1m 2회 측정.
> 관측: coupon_5xx=0, http_req_failed=0%(409 제외), coupon_applied ≤ buyerCount(이중사용 0),
> coupon_order_duration p95=19~23ms, p99=52~138ms(성공경로 소표본 꼬리라 변동 큼).

| metric | 값 | 상태 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 확정 (409 제외됨) |
| `coupon_5xx` | `count==0` | 확정 |
| `coupon_order_duration p95` | `p(95)<60` | 확정 — 베이스라인 p95≈23ms × ~2.6 |
| `coupon_order_duration p99` | `p(99)<300` | 확정 — 베이스라인 p99≈138ms × ~2.2(소표본 꼬리 변동 흡수) |
| `coupon_conflict` | 임계 없음 | 가시화만 (정상 비즈니스 흐름) |
| `coupon_applied` | 임계 없음 | 이중사용 0 신호로 수동 해석 |

> **coupon_applied 변동**: 런마다 활성 VU 수만큼만 "첫 사용 성공"(나머지는 같은 쿠폰 재시도 409)이라 coupon_applied가 런별로 다르다(소비성 특성). 핵심은 **buyerCount를 넘지 않음**(이중사용 0).

### 재측정 절차 (메인)

1. 운영 안전 게이트 확인 (notification 정지 + Kafka up)
2. perf DB TRUNCATE (깨끗한 DB)
3. `-e PROFILE=load -e SUMMARY_EXPORT_PATH=shop-core/perf/k6/baselines/coupon-apply-load.json` 실행
4. 관측값 × ~2.5~3 여유율로 `lib/config.js` `COUPON_THRESHOLDS.coupon_order_duration` 갱신

### 커스텀 메트릭

| 메트릭 | 종류 | 설명 |
|---|---|---|
| `coupon_applied` | Counter | 201 & discountAmount>0 (쿠폰 적용 주문 성공) |
| `coupon_conflict` | Counter | 409 (이미 사용된 쿠폰 — 중복 사용 차단 정상 흐름) |
| `coupon_5xx` | Counter | >=500 (직렬화 붕괴 징후) — threshold: count==0 |
| `coupon_order_duration` | Trend | 쿠폰 적용 주문 POST 자체 지연 (ms) |

---

## 13. catalog-read 시나리오 — 가상스레드 A/B 측정 (Task 006)

> Plan: `docs/plans/performance/006-shop-core-virtual-thread-ab-evaluation-plan.md`
>
> 공개 상품 목록/상세 GET 고동시성 가압 — VT vs 플랫폼 스레드 A/B 비교.
> 락 없는 DB 읽기 경로로 "VT에 공정한 기회"를 주는 주 측정 경로(plan §2.3·§2.4·§3).

### 읽기 경로가 VT 측정에 적합한 이유

- 공개 GET은 락·트랜잭션 경합 없음 → throughput 상한이 HikariCP 풀이 아닌 **스레드·OS 스케줄러** 에 의해 결정.
- 플랫폼 스레드(기본 200)를 초과하는 동시 요청(300/500 VU)을 투입하면 스레드 풀 큐잉이 발생 → VT는 1:1 요청-스레드로 큐잉 없이 처리 가능 → 이 차이가 VT 이득의 측정 대상.
- 주문 쓰기(order-create)는 DB 커넥션 풀(≈80~90)이 스레드 수(200)보다 먼저 캡 → 풀이 병목이라 VT 전환 효과가 작을 수 있음. 읽기는 그 캡이 없어 더 넓은 동시성 구간 측정 가능.

### ★ 운영 안전 (읽기 전용)

- 이 시나리오는 **읽기 전용**이므로 주문/Outbox/Kafka 이벤트가 전혀 발생하지 않는다.
- notification 서비스와 **완전 무관** — 메일 대량 발송 위험 없음(안전).
- setup()에서 seller signup 1회가 발생하나 횟수가 적어 실용 무해. 그래도 일관성을 위해 notification log 모드 권장.

### REST 계약 (SecurityConfig 확인)

| 엔드포인트 | 인증 | 비고 |
|---|---|---|
| `GET /api/v1/products?page=0&size=20&sort=latest` | **permitAll** | 목록 (PageResponse) |
| `GET /api/v1/products/{productId}` | **permitAll** | 상세 (PublicProductDetailResponse) |

응답 구조:
- 목록: `{ content: [{productId, name, displayPrice, ...}], page, size, totalElements, totalPages }`
- 상세: `{ productId, name, description, displayPrice, soldOut, category, images, options, variants }`

### conc 프로파일 (A/B 측정 주 프로파일)

- **모델**: closed (VU 수만큼 항상 동시 요청 유지 — "진짜 동시성").
- **VU**: `CONC_VUS` 환경변수로 조절 (기본 300). A/B 스윕: 100 / 300 / 500.
- **duration**: `CONC_DURATION` 환경변수로 조절 (기본 40s).
- **thresholds**: `READ_THRESHOLDS` (read_5xx count==0 + http_req_failed rate<0.01).
  http_req_duration 임계 없음 — 곡선 측정이 목적.

### 환경변수

| 변수명 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 가압 대상 앱 루트 |
| `PROFILE` | `conc` | `conc` \| `smoke` \| `load` \| `stress` |
| `CONC_VUS` | `300` | conc 프로파일 VU 수. A/B 스윕: 100/300/500 |
| `CONC_DURATION` | `40s` | conc 프로파일 지속 시간 |
| `ADMIN_EMAIL` | `admin@example.com` | admin 계정 이메일 |
| `ADMIN_PASSWORD` | `Admin1234!` | admin 계정 비밀번호 |
| `RUN_TAG` | uuidv4 자동 | 런별 유니크 prefix |
| `SUMMARY_EXPORT_PATH` | (없음) | 메트릭 전용 JSON 출력 경로 (sellerToken 제외) |

### A/B 측정 — 3단 풀 × (플랫폼/VT) 매트릭스 (plan §3)

각 셀마다 앱을 재기동 후 k6 실행. notification은 측정 내내 정지/log 모드.

| 풀 크기 | 앱 기동 플래그 | CONC_VUS | 커맨드 |
|---|---|---|---|
| 10 (현행 기본) | — | 300 | 아래 참조 |
| 10 (현행 기본) | `-Dshop.threads.virtual.enabled=true` | 300 | 아래 참조 |
| 50 | `SHOP_CORE_HIKARI_MAX_POOL=50` | 300 | 아래 참조 |
| 50 | `SHOP_CORE_HIKARI_MAX_POOL=50 -Dshop.threads.virtual.enabled=true` | 300 | 아래 참조 |
| 250 | `SHOP_CORE_HIKARI_MAX_POOL=250` + PG max_conn=300 | 300 | 아래 참조 |
| 250 | 위 + `-Dshop.threads.virtual.enabled=true` | 300 | 아래 참조 |

### smoke 실행 (동작 확인)

```bash
# Windows (k6 전체 경로)
mkdir -p build/k6
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=smoke `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/catalog-read-smoke.json `
  shop-core/perf/k6/scenarios/catalog-read.js

# macOS/Linux
mkdir -p build/k6
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=smoke \
  -e SUMMARY_EXPORT_PATH=build/k6/catalog-read-smoke.json \
  shop-core/perf/k6/scenarios/catalog-read.js
```

### conc 실행 (A/B 측정 — VU 스윕)

```bash
# --- CONC_VUS=100 (플랫폼 스레드 미포화 — 베이스라인) ---
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=conc `
  -e CONC_VUS=100 `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/catalog-read-conc100.json `
  shop-core/perf/k6/scenarios/catalog-read.js

# --- CONC_VUS=300 (플랫폼 스레드 포화 → 큐잉 — 핵심 측정점) ---
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=conc `
  -e CONC_VUS=300 `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/catalog-read-conc300.json `
  shop-core/perf/k6/scenarios/catalog-read.js

# --- CONC_VUS=500 (큐잉 심화 — 포화 곡선) ---
& "C:\Program Files\k6\k6.exe" run `
  -e PROFILE=conc `
  -e CONC_VUS=500 `
  -e BASE_URL=http://localhost:8080 `
  -e SUMMARY_EXPORT_PATH=build/k6/catalog-read-conc500.json `
  shop-core/perf/k6/scenarios/catalog-read.js
```

> **참고**: `--summary-export` 대신 `-e SUMMARY_EXPORT_PATH`를 사용한다. `handleSummary` 구현으로 setup_data(sellerToken)가 제거된 메트릭 전용 JSON을 내보낸다.

### READ_THRESHOLDS (진단용)

| metric | 값 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 공개 GET은 401/403 없음. 실질=5xx+타임아웃 |
| `read_5xx` | `count==0` | 읽기 5xx=서버 오류. 반드시 0이어야 함 |
| `http_req_duration` | 임계 없음 | VT vs 플랫폼 스레드 곡선 측정이 목적 |
| `read_duration` | 임계 없음 | JSON 아티팩트로 p95/p99 추출해 비교표 기록 |

### 커스텀 메트릭

| 메트릭 | 종류 | 설명 |
|---|---|---|
| `read_duration` | Trend | GET 요청 지연 (목록·상세 통합, ms). A/B 비교 주 지표 |
| `read_5xx` | Counter | >=500. threshold: count==0 |

### 시드 설계 (setupCatalogSeed)

- seller 1개 생성 → 상품 50개(CATALOG_PRODUCT_COUNT) ON_SALE 게시 + variant 생성.
- buyer 생성 없음 (읽기는 인증 불필요).
- 기존 `setupSeed` / `setupCouponSeed` 완전 독립 — 무영향.
- 상품명 prefix `PERF-Catalog`로 `setupSeed` 상품(PERF-Product)과 네임스페이스 분리.

---

## 14. 비범위 (후속 Task 예정)

- 한도형(B) 경계 경합 단독 시나리오 — coupon-apply 후속 옵션
- 지속 성공-경로 throughput (쿠폰 풀 대량 시드) — 소비성 한계로 후속 옵션
- Grafana/InfluxDB 시계열 파이프라인, 분산 부하(k6 Cloud) — 보류(YAGNI)
- teardown() self-clean — 후속 Task
- 순수 결제 고립(풀 시드 방식 (b)) — 후속 옵션 (plan §8)
