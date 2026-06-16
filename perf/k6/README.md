# shop-core k6 부하 테스트 하니스

> 대상 Task: `docs/tasks/performance/001-performance-shop-core-k6-order-create-smoke-baseline.md`
> Plan: `docs/plans/performance/001-shop-core-k6-order-create-smoke-baseline-plan.md`

## 디렉토리 구조

```
shop-core/perf/k6/
  lib/
    config.js        # BASE_URL·PROFILE·thresholds·시드 상수 (공통)
    auth.js          # 로그인·회원가입·Bearer 헤더 헬퍼
    seed.js          # setup() 전용 — seller/상품/variant/buyer 시드
  scenarios/
    order-create.js  # 핫패스: cart add → order create + 커스텀 메트릭
  profiles/
    smoke.js         # 5 VU × 30s options export
  README.md          # 이 문서
```

산출 JSON:
- **런타임 export**: `build/k6/order-create-smoke.json` (smoke 실행 시 `--summary-export`로 생성). `build/`는 gitignore 대상 — 일회성·비커밋.
- **커밋된 베이스라인(추세 비교 기준)**: `shop-core/perf/k6/baselines/order-create-smoke.json`. 새 기준을 세울 때만 의도적으로 갱신·커밋한다.

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
& "C:\Program Files\k6\k6.exe" run ^
  -e PROFILE=smoke ^
  -e BASE_URL=http://localhost:8080 ^
  shop-core/perf/k6/scenarios/order-create.js ^
  --summary-export=build/k6/order-create-smoke.json

# macOS/Linux (k6 PATH 등록된 경우)
BASE_URL=http://localhost:8080 k6 run \
  -e PROFILE=smoke \
  shop-core/perf/k6/scenarios/order-create.js \
  --summary-export=build/k6/order-create-smoke.json
```

`build/k6/` 디렉토리가 없으면 미리 생성한다.

```bash
mkdir -p build/k6
```

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

## 5. 환경변수

| 변수명 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 가압 대상 앱 루트 |
| `PROFILE` | `smoke` | 부하 프로파일 (`smoke` 외 후속 Task에서 추가 예정) |
| `ADMIN_EMAIL` | `admin@example.com` | admin 계정 이메일 |
| `ADMIN_PASSWORD` | `Admin1234!` | admin 계정 비밀번호 |
| `RUN_TAG` | uuidv4 자동 생성 | 런별 유니크 prefix (데이터 네임스페이스) |

---

## 6. thresholds 확정 절차

현재 `lib/config.js`의 thresholds는 **PLACEHOLDER(보수적 초기값)**이다. smoke 1회 실행 후 아래 절차로 실측값으로 교체한다.

1. smoke 실행 → 터미널 summary에서 `http_req_duration` p95/p99, `http_req_failed` rate 확인
2. `lib/config.js`의 값을 관측값 × 여유율(개발 머신 단일 런·CI 하드웨어 편차 흡수)로 교체
3. 주석을 `// 베이스라인: YYYY-MM-DD 측정 (smoke, 5VU×30s)` 형태로 갱신
4. 런타임 export(`build/k6/...`)를 커밋된 베이스라인 `shop-core/perf/k6/baselines/order-create-smoke.json`로 복사·갱신해 추세 비교 기준으로 삼음 (`build/`는 gitignore라 직접 커밋 불가)

현재 thresholds (베이스라인 2026-06-16 확정):

| metric | 값 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 에러율 1% 미만 |
| `http_req_duration p95` | `p(95)<100` | 베이스라인 p95≈40ms × ~2.5 여유 |
| `http_req_duration p99` | `p(99)<200` | 베이스라인 p99≈53ms × ~3.8 여유 |
| `order_5xx` | `count==0` | 락 붕괴=비정상, 0이어야 함 |

---

## 7. 커스텀 메트릭

| 메트릭 | 종류 | 설명 |
|---|---|---|
| `order_created` | Counter | 주문 생성 성공(201) 누적 수 |
| `order_conflict` | Counter | 409 (낙관 충돌·재고 부족) — 가시화용, threshold 없음 |
| `order_5xx` | Counter | 500 이상 — threshold: count==0 |
| `order_create_duration` | Trend | 주문 POST 자체 지연 (ms) |

---

## 8. 데이터 오염 방지

- 런마다 `RUN_TAG`(또는 uuidv4) prefix로 seller/상품/buyer 이메일·SKU를 네임스페이스 분리한다.
- perf 전용 DB(별도 `shop_core_perf` 데이터베이스)를 권장한다.
- 시드 데이터 self-clean은 후속 Task에서 teardown()으로 구현 예정이다.

---

## 9. Kafka 오프 시 충실도 저하 경고

Kafka가 중단된 상태에서 smoke를 실행하면:
- 주문 생성 후 Outbox 이벤트 발행이 실패하거나 타임아웃이 발생할 수 있다.
- `order_5xx` counter가 증가하고 `http_req_failed` threshold가 위반될 수 있다.
- 이 경우 성능 수치가 "정상 운영" 기준이 아니므로 베이스라인으로 사용하지 않는다.

반드시 `docker compose -f docker/shop/docker-compose.yml up -d` 로 인프라를 완전히 기동한 후 실행한다.

---

## 10. 비범위 (후속 Task 예정)

- `profiles/load.js`, `profiles/stress.js` — 후속 Task
- `scenarios/payment-confirm.js`, `scenarios/coupon-apply.js` — 후속 Task
- Grafana/InfluxDB 시계열 파이프라인, 분산 부하(k6 Cloud) — 보류(YAGNI)
- teardown() self-clean — 후속 Task
