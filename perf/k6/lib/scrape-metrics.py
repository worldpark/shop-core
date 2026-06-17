#!/usr/bin/env python3
"""
scrape-metrics.py — 서버측 메트릭 스크랩 스크립트 (측정 하니스)

/actuator/prometheus 폴링 → 지정 메트릭을 타임스탬프와 함께 CSV로 적재.
saturate 런(또는 다른 k6 런)과 동시에 기동해 서버측 진단 데이터를 수집한다.

출력 형식: long format (ts, metric, value)
  ts        — Unix epoch 초 (float, 시스템 시계)
  metric    — 메트릭명 (라벨 제거 후 기명 식별자)
  value     — 추출된 수치 (float)

수집 메트릭 (Prometheus exposition 라벨 붙은 라인도 매칭):
  hikaricp_connections_active       — 활성 커넥션 수
  hikaricp_connections_pending      — 대기 커넥션 수 (>0 지속 = 풀 포화 핵심 신호)
  hikaricp_connections_max          — 풀 최대 크기 (셀 값 검증용: 10/30/50)
  hikaricp_connections_acquire_seconds_count  — 누적 획득 시도 수
  hikaricp_connections_acquire_seconds_sum    — 누적 획득 지연 합계(초)
  jvm_threads_live_threads          — 현재 라이브 스레드 수
  jvm_threads_peak_threads          — 피크 스레드 수
  process_cpu_usage                 — 앱 프로세스 CPU 사용률 (0.0~1.0; ≈1.0 = CPU 병목)
  system_cpu_usage                  — 시스템 전체 CPU 사용률 (공유 머신 판단용)

사용:
  python3 scrape-metrics.py [options]

  -u / --base-url  BASE_URL   타겟 앱 루트 (기본: http://localhost:8080)
  -o / --output    PATH       출력 CSV 경로 (기본: build/k6/server-metrics.csv)
  -i / --interval  SEC        폴링 간격 초 (기본: 3)
  -d / --duration  SEC        총 수집 시간 초 (0 = Ctrl-C까지, 기본: 0)

예:
  # saturate 런(약 4분)과 동시에 기동 — Ctrl-C로 종료
  python3 shop-core/perf/k6/lib/scrape-metrics.py -o build/k6/server-metrics.csv

  # 300초 자동 종료
  python3 shop-core/perf/k6/lib/scrape-metrics.py -d 300 -o build/k6/server-metrics.csv

앱 미기동 시:
  연결 실패를 로깅 후 다음 폴링 주기까지 대기(죽지 않음).
  앱 기동 후 자동 재연결.

CSV 경로가 없으면 디렉토리까지 자동 생성. build/k6/ 는 gitignore 대상.
"""

import argparse
import csv
import os
import re
import sys
import time
try:
    from urllib.request import urlopen
    from urllib.error import URLError
except ImportError:
    # Python 2 fallback (이 프로젝트는 3만 지원 — 보험 목적 임포트)
    print("Python 3 이상 필요합니다.", file=sys.stderr)
    sys.exit(1)

# ---------------------------------------------------------------------------
# 수집 대상 메트릭 정의
#
# 각 항목: (출력 컬럼명, Prometheus 메트릭명 정규식 패턴)
# Prometheus exposition 형식: <metric_name>[{label="val",...}] <value> [timestamp]
# 라벨 붙은 라인도 매칭: 메트릭명 뒤에 '{...}' 허용.
# ---------------------------------------------------------------------------
METRIC_PATTERNS = [
    # HikariCP 커넥션 풀
    ('hikaricp_connections_active',
     r'^hikaricp_connections_active(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    ('hikaricp_connections_pending',
     r'^hikaricp_connections_pending(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    ('hikaricp_connections_max',
     r'^hikaricp_connections_max(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    ('hikaricp_connections_acquire_seconds_count',
     r'^hikaricp_connections_acquire_seconds_count(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    ('hikaricp_connections_acquire_seconds_sum',
     r'^hikaricp_connections_acquire_seconds_sum(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    # JVM 스레드
    ('jvm_threads_live_threads',
     r'^jvm_threads_live_threads(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    ('jvm_threads_peak_threads',
     r'^jvm_threads_peak_threads(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    # CPU
    ('process_cpu_usage',
     r'^process_cpu_usage(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
    ('system_cpu_usage',
     r'^system_cpu_usage(?:\{[^}]*\})?\s+([\d.]+(?:e[+-]?\d+)?)'),
]

# 컴파일된 패턴 캐시 (초기화 1회)
_COMPILED = [(name, re.compile(pat, re.MULTILINE)) for name, pat in METRIC_PATTERNS]


def fetch_prometheus(base_url, timeout=5):
    """
    /actuator/prometheus 엔드포인트를 요청해 텍스트 반환.
    실패 시 None 반환(호출자가 graceful 처리).
    """
    url = base_url.rstrip('/') + '/actuator/prometheus'
    try:
        with urlopen(url, timeout=timeout) as resp:
            return resp.read().decode('utf-8', errors='replace')
    except URLError as e:
        return None
    except Exception as e:
        return None


def extract_metrics(text, ts):
    """
    Prometheus exposition 텍스트에서 수집 대상 메트릭을 추출해
    (ts, metric_name, value) 튜플 리스트로 반환.
    같은 메트릭이 여러 라벨 조합으로 있으면 첫 번째 매치만 사용(단일 풀 전제).
    """
    rows = []
    for name, pattern in _COMPILED:
        m = pattern.search(text)
        if m:
            try:
                value = float(m.group(1))
                rows.append((ts, name, value))
            except ValueError:
                pass
    return rows


def parse_args():
    parser = argparse.ArgumentParser(
        description='shop-core actuator/prometheus 폴링 → 서버측 메트릭 CSV 적재',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        '-u', '--base-url',
        default='http://localhost:8080',
        help='타겟 앱 루트 URL (기본: http://localhost:8080)',
    )
    parser.add_argument(
        '-o', '--output',
        default='build/k6/server-metrics.csv',
        help='출력 CSV 경로 (기본: build/k6/server-metrics.csv)',
    )
    parser.add_argument(
        '-i', '--interval',
        type=float,
        default=3.0,
        help='폴링 간격 초 (기본: 3)',
    )
    parser.add_argument(
        '-d', '--duration',
        type=float,
        default=0.0,
        help='총 수집 시간 초 (0 = Ctrl-C까지, 기본: 0)',
    )
    return parser.parse_args()


def ensure_dir(path):
    """출력 파일의 디렉토리가 없으면 생성."""
    d = os.path.dirname(path)
    if d and not os.path.exists(d):
        os.makedirs(d, exist_ok=True)


def main():
    args = parse_args()

    base_url = args.base_url.rstrip('/')
    output_path = args.output
    interval = max(0.1, args.interval)
    duration = args.duration  # 0 = 무제한

    ensure_dir(output_path)

    # CSV 파일 오픈 (append 모드 — 기존 런 이어쓰기 방지를 위해 write 모드로 시작)
    csv_file = open(output_path, 'w', newline='', encoding='utf-8')
    writer = csv.writer(csv_file)
    writer.writerow(['ts', 'metric', 'value'])  # 헤더
    csv_file.flush()

    print(
        f"[scrape-metrics] 시작 — 타겟: {base_url}/actuator/prometheus",
        file=sys.stderr,
    )
    print(
        f"[scrape-metrics] 출력: {output_path}  간격: {interval}s  "
        f"지속: {'Ctrl-C까지' if duration == 0 else f'{duration}s'}",
        file=sys.stderr,
    )

    start_time = time.time()
    poll_count = 0
    miss_count = 0

    try:
        while True:
            now = time.time()

            # 지속 시간 초과 시 종료
            if duration > 0 and (now - start_time) >= duration:
                break

            ts = now
            text = fetch_prometheus(base_url)

            if text is None:
                miss_count += 1
                print(
                    f"[scrape-metrics] 경고: {base_url}/actuator/prometheus 연결 실패 "
                    f"(누적 {miss_count}회) — {interval}s 후 재시도",
                    file=sys.stderr,
                )
            else:
                rows = extract_metrics(text, ts)
                if rows:
                    writer.writerows(rows)
                    csv_file.flush()
                    poll_count += 1
                    if poll_count <= 3 or poll_count % 10 == 0:
                        # 초반 3회 + 10회마다 진행 상황 출력
                        sample = {r[1]: r[2] for r in rows}
                        active = sample.get('hikaricp_connections_active', 'n/a')
                        pending = sample.get('hikaricp_connections_pending', 'n/a')
                        cpu = sample.get('process_cpu_usage', 'n/a')
                        print(
                            f"[scrape-metrics] poll#{poll_count} ts={ts:.1f} "
                            f"hikari_active={active} hikari_pending={pending} "
                            f"process_cpu={cpu}",
                            file=sys.stderr,
                        )
                else:
                    print(
                        f"[scrape-metrics] 경고: 메트릭 0건 추출 — 앱 기동 중이거나 "
                        "prometheus endpoint 미노출일 수 있음",
                        file=sys.stderr,
                    )

            # 다음 폴링까지 대기 (처리 시간 감안)
            elapsed = time.time() - now
            sleep_sec = max(0.0, interval - elapsed)
            time.sleep(sleep_sec)

    except KeyboardInterrupt:
        print(
            f"\n[scrape-metrics] Ctrl-C 수신 — 종료 (poll={poll_count}, miss={miss_count})",
            file=sys.stderr,
        )
    finally:
        csv_file.close()
        print(
            f"[scrape-metrics] 완료 — CSV: {output_path}  총 폴링: {poll_count}  연결실패: {miss_count}",
            file=sys.stderr,
        )


if __name__ == '__main__':
    main()
