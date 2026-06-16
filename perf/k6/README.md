# shop-core k6 부하 테스트 하니스

> 대상 Task: `docs/tasks/performance/001-performance-shop-core-k6-order-create-smoke-baseline.md` (Task 001)
>           `docs/tasks/performance/002-performance-shop-core-k6-order-create-load-profile.md` (Task 002)
> Plan: `docs/plans/performance/001-shop-core-k6-order-create-smoke-baseline-plan.md`
>       `docs/plans/performance/002-shop-core-k6-order-create-load-profile-plan.md`

## 디렉토리 구조

```
shop-core/perf/k6/
  lib/
    config.js        # BASE_URL·PROFILE·thresholds(smoke/load 분리)·시드 상수 (공통)
    auth.js          # 로그인·회원가입·Bearer 헤더 헬퍼
    seed.js          # setup() 전용 — seller/상품/variant/buyer 시드
  scenarios/
    order-create.js  # 핫패스: cart add → order create + 커스텀 메트릭 (smoke/load 분기)
  profiles/
    smoke.js         # 5 VU × 30s options export (closed 모델)
    load.js          # 100 rps × 1m options export (open: constant-arrival-rate)
  baselines/
    order-create-smoke.json  # smoke 베이스라인 (Task 001, 2026-06-16)
    order-create-load.json   # load 베이스라인 (Task 002, 2026-06-16)
  README.md          # 이 문서
```

산출 JSON:
- **런타임 export**: `build/k6/order-create-{smoke,load}.json` (`--summary-export`로 생성). `build/`는 gitignore 대상 — 일회성·비커밋.
- **커밋된 베이스라인(추세 비교 기준)**: `shop-core/perf/k6/baselines/order-create-{smoke,load}.json`. 새 기준을 세울 때만 의도적으로 갱신·커밋한다.

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
- **목표**: 100 rps × 1분 (2026-06-16 §6.5 실측 확정).
- **VU 풀**: preAllocatedVUs=20, maxVUs=50. 응답 지연 시 VU를 추가 투입.
- **dropped_iterations**: VU 풀 한계 도달 시 발생. 과도한 dropped는 `maxVUs` 상향 또는 목표 RPS 하향 신호.

### 목표 RPS 확정 근거 (2026-06-16 §6.5 판정, 깨끗한 DB 기준 2026-06-16 재확정)

| RPS | p95 | p99 | dropped/s | 판정 |
|---|---|---|---|---|
| 100 | 18ms | 33~62ms | 0 | SLO 충족 (확정 목표) — 깨끗한 DB 기준, baseline JSON p99=61.54ms |
| 200 | 634ms | — | 71.9 | 한계 초과 (stress 영역) |
| 300 | 886ms | — | 138.5 | 완전 한계 초과 |

- 앱 실제 처리 상한선 ≈ **90~100 orders/s** (PESSIMISTIC_WRITE 단일 variant 경합 특성).
- 100 rps에서 smoke(p95=40ms) 대비 load(p95=18ms, 깨끗한 DB) — open 모델(constant-arrival-rate)이 VU 경합 없이 고르게 분산하여 smoke보다 빠른 것은 정상 동작.
- 이전 누적 상태(orders 41,531개) 측정(2026-06-16): p95=242ms → 깨끗한 DB(p95=18ms) 대비 13배 저하. 누적 데이터는 성능에 심각한 영향을 미치므로 베이스라인은 반드시 깨끗한 DB 기준으로 측정한다.
- 한계 탐색은 **stress(Task 003)** 으로 이양.

---

## 6. 환경변수

| 변수명 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 가압 대상 앱 루트 |
| `PROFILE` | `smoke` | 부하 프로파일 (`smoke` \| `load`) |
| `ADMIN_EMAIL` | `admin@example.com` | admin 계정 이메일 |
| `ADMIN_PASSWORD` | `Admin1234!` | admin 계정 비밀번호 |
| `RUN_TAG` | uuidv4 자동 생성 | 런별 유니크 prefix (데이터 네임스페이스) |

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

베이스라인(2026-06-16 재확정, 깨끗한 DB 기준, 100rps×1m):

| metric | 값 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 에러율 1% 미만 |
| `http_req_duration p95` | `p(95)<50` | 깨끗한 DB 기준 p95≈18ms × ~2.5 여유 → 50ms |
| `http_req_duration p99` | `p(99)<100` | 깨끗한 DB 클린 p99 상한 ≈62ms(baseline JSON 61.54ms, 런별 변동 33~62ms) → 100ms (변동 전부 통과, 회귀 >100 감지) |
| `order_5xx` | `count==0` | 락 붕괴=비정상, 0이어야 함 |
| `dropped_iterations` | `rate<3` | 깨끗한 DB 실측 0/s 허용, 실질 under-provision(71~138/s) 차단 |

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

## 11. 비범위 (후속 Task 예정)

- `profiles/stress.js` — Task 003 (한계 탐색, 점증 부하)
- `scenarios/payment-confirm.js`, `scenarios/coupon-apply.js` — Task 004, 005
- Grafana/InfluxDB 시계열 파이프라인, 분산 부하(k6 Cloud) — 보류(YAGNI)
- teardown() self-clean — 후속 Task
