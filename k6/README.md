# 배송 업무 수령 경합 부하 테스트

JUnit 동시성 테스트(`DeliveryPlanClaimConcurrencyTest`)가 **정확성**을 증명한다면,
이 스크립트는 그 보장이 실제 HTTP 부하에서 **어떤 비용으로** 지켜지는지를 관측한다.

## 준비

```bash
brew install k6
docker compose up -d          # prometheus, grafana, tempo, redis
./gradlew bootRun             # 앱 (MySQL, KAKAO_LOCAL_API_KEY 필요)
```

계획 등록 시 카카오 지오코딩을 호출하므로 `KAKAO_LOCAL_API_KEY` 가 설정되어 있어야 한다.
`setup()` 단계에서 업무 10건을 만들며 주소 2개씩, 총 20회 호출한다.

## 실행

### 순수 경합 측정 (권장)

우선 수령 윈도우를 끄고 띄운다. 켜져 있으면 초반 60초가 전부 `priority_blocked` 로 채워져
선착순 경합 자체를 관측하기 어렵다.

```bash
DELIVERY_PRIORITY_WINDOW_ENABLED=false ./gradlew bootRun
```

```bash
k6 run k6/claim-contention.js
```

### Grafana 로 실시간 관측

k6 자체 지표까지 Prometheus 로 보내려면 remote write 출력을 쓴다.
(앱 쪽 지표는 이미 Prometheus 가 5초마다 스크랩하므로 이 옵션 없이도 대시보드는 움직인다.)

```bash
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
k6 run -o experimental-prometheus-rw k6/claim-contention.js
```

> k6 v1.0 이상에서는 출력 이름이 `prometheus-rw` 로 바뀌었다. `k6 version` 을 확인하고 맞춰 쓴다.

대시보드: http://localhost:3000 → `Delivery Insight` 폴더 → **배송 업무 수령 경합** (admin / admin)

### 우선 수령 윈도우 관측

윈도우를 켠 채로 2분 이상 돌리면 구간 전환이 그래프로 보인다.

```bash
./gradlew bootRun    # 기본값 enabled=true, 60초
k6 run --duration 150s k6/claim-contention.js
```

초반에는 `priority_blocked` 가 대부분을 차지하다가, 공개 시각이 지나면
`conflict` 와 `success` 로 넘어간다. 반납된 업무는 `public_at` 이 비워져 이후로는 계속 전체 공개다.

## 옵션

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | 대상 서버 |
| `VUS` | `50` | 동시 가상 사용자 수 |
| `DURATION` | `60s` | 부하 지속 시간 |
| `DRIVERS` | `20` | 생성할 부하 테스트용 기사 계정 수 |
| `PLANS` | `10` | 경합 대상 업무 수 |
| `SLEEP` | `0.2` | 반복 사이 대기(초) |
| `PROFILE` | `contention` | `contention`(고정 VU 경합) 또는 `stress`(포화 지점 탐색) |
| `PEAK_VUS` | `300` | `PROFILE=stress` 일 때 최종 목표 VU |
| `RAMP_SECONDS` | `15` | 각 목표 VU 까지 올리는 시간 |
| `HOLD_SECONDS` | `45` | 각 목표 VU 를 유지하는 시간. 집계 대상 구간 |
| `ADMIN_ID` / `ADMIN_PW` | `admin` / `1234` | 업무 등록에 쓸 관리자 계정 |

```bash
VUS=100 DURATION=3m PLANS=5 k6 run k6/claim-contention.js
```

업무 수를 줄이고 VU 를 늘릴수록 경합이 세진다.

## 시나리오 설계

```
VU ─ claim ─┬─ 200 ─ release ─┐
            │                 └─→ 업무가 다시 OPEN 으로 돌아감
            └─ 409 (conflict / limit / priority / lock)
```

수령에 성공한 VU 가 **곧바로 반납**해 업무를 풀에 되돌린다. 덕분에

- 적은 수의 업무만으로 지속적인 경합을 만들 수 있고 (지오코딩 호출 최소화)
- `claim` 과 `release` 양쪽 경로가 모두 부하 구간에 들어간다

반납이 두 번 다 실패하면 그 업무는 풀에서 빠지므로 `release_failed` 로 따로 센다.

## 포화 지점 탐색

```bash
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
k6 run -o experimental-prometheus-rw \
  -e PROFILE=stress -e PEAK_VUS=400 -e PLANS=3 \
  k6/claim-contention.js 2>&1 | tee k6/run-stress-400.log
```

remote-write 를 켜야 Grafana 에서 `k6_vus` 시계열과 서버 지표를 **같은 시간축에 정렬**해 볼 수 있다.
이게 없으면 p99 가 튄 시점의 실제 VU 를 대시보드에서 맞춰볼 수 없다.

### 단계 구성

각 목표 VU 까지 `RAMP_SECONDS`(기본 15초) 동안 올린 뒤 `HOLD_SECONDS`(기본 45초) 동안 **유지**한다.

```
 0s ──ramp──▶ 60 VU ──── 45s 유지 ────▶ ramp ─▶ 140 VU ─── 45s 유지 ───▶ ... ─▶ 400 VU ─ 45s 유지 ─▶ ramp-down
     15s          15~60s                  15s        75~120s                        195~240s          (255s 종료)
```

**집계는 유지 구간 표본만 쓴다.** ramp 상승 구간과 마지막 ramp-down 은 태깅하지 않아 섞이지 않는다.
계속 상승하는 ramp 를 구간으로 자르면 버킷마다 폭과 체류 시간이 달라져,
선형으로 잘 확장되는 서버도 포화로 오판하게 된다.

### 구간별 지연

리포트에 이 표가 나온다. 모든 구간의 유지 시간이 동일하므로 **시도/초를 그대로 비교**할 수 있다.

| 유지 VU | 수령 시도 | 시도/초 | p50 | p90 | p95 | p99 | max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 60 | ... | ... | ... | ... | ... | ... | ... |
| 140 | | | | | | | |
| 280 | | | | | | | |
| 400 | | | | | | | |

**VU 가 2배가 될 때 시도/초가 2배로 늘지 않기 시작하는 구간**이 포화 후보다.
같은 시점에 p95·p99 가 함께 꺾이는지 확인한다.

### `SLEEP` 이 한계를 가린다

`SLEEP` 이 남아 있으면 VU 하나가 낼 수 있는 초당 요청이 `1/SLEEP` 으로 묶인다.
부하가 VU 에 비례해 제한되므로 **서버가 이미 포화인데도 확장 효율이 1.0 근처로 나온다.**

| 실행 | SLEEP | peak VU | 최대 처리량 | 확장 효율 |
| --- | ---: | ---: | ---: | ---: |
| A | 0.2 | 400 | 1,684/s | 0.96x (선형처럼 보임) |
| B | 0 | 1000 | 2,574/s | 0.50x (포화 드러남) |

A 는 400 VU × 최대 5회/초 = 2,000/s 가 상한이라 서버 한계(약 2,500/s)에 닿기 전에 끝났다.
**포화 지점을 찾을 때는 반드시 `-e SLEEP=0` 으로 돌린다.** `SLEEP` 은 현실적인 사용 패턴을 흉내 낼 때만 쓴다.

### 병목 후보 좁히기

부하 수치만으로 원인을 단정할 수 없다. 아래 패널은 **원인을 특정하는 도구가 아니라 후보를 좁히는 도구**다.

| 패널 | 좁혀지는 후보 |
| --- | --- |
| 커넥션 획득 대기 vs 점유 시간 | 획득 대기만 오르고 점유 시간이 평탄 → 풀 크기 부족 쪽. **둘 다 오르면 DB 가 느려 커넥션이 오래 잡히는 것**이라 풀을 키우면 오히려 악화된다 |
| HikariCP `pending` | 커넥션 획득 대기가 있다는 사실만 말해준다. 풀 크기가 원인이라는 뜻은 아니다 |
| JVM GC 일시정지 | GC 급증 구간과 p99 급등 구간이 겹치면 GC 후보 |
| CPU 사용률 | 애플리케이션 CPU 포화 여부 |

CPU·GC·힙이 여유롭고 p99 만 오른다고 해서 **DB 락으로 단정할 수 없다.**
DB CPU/IO, 웹 요청 스레드, 네트워크, 로깅도 후보로 남는다. 이를 좁히려면 DB 지표가 필요하다.

```bash
DB_USERNAME=root DB_PASSWORD=<비번> docker compose --profile db-metrics up -d
```

`mysqld-exporter` 가 뜨면 대시보드의 **MySQL** 패널에 실행 중 스레드, 행 락 대기 횟수·평균 시간이 들어온다.
띄우지 않으면 그 패널은 No data 이고, **DB 내부는 관측 범위 밖**이라는 뜻이다.

## 판정 기준 (thresholds)

| 지표 | 기준 | 의미 |
| --- | --- | --- |
| `claim_unexpected` | `count==0` | 200/409 외의 응답이 하나라도 나오면 실패 |
| `release_failed` | `count==0` | 반납이 실패하면 그 업무가 경합 풀에서 이탈해 **부하 강도가 조용히 낮아진다.** 지연만 보면 통과해 버리므로 따로 고정한다 |
| `release_already_returned` | `count==0` | VU 와 기사가 1:1(기본값)일 때만 적용 |
| `plans_not_returned` | `count==0` | 종료 시점에 모든 업무가 OPEN 으로 복귀했는지. `stress` 프로필에서는 램프다운 중 잘린 반복이 있을 수 있어 제외 |
| `http_req_duration{endpoint:claim}` | `p(95)<500ms`, `p(99)<1.5s` | 락 대기가 꼬리 지연을 얼마나 밀어 올리는지 |
| `http_req_duration{endpoint:release}` | `p(95)<500ms` | 반납 경로 |

### `http_req_failed` 는 보지 말 것

409 는 **정상 동작**이다. 경합에서 밀린 요청이므로 실패가 아니다.
실제 실행에서 `http_req_failed` 가 68% 로 찍히지만 그 대부분이 정상 409 다.
장애율로 읽으면 안 되고, 위 지표로만 판정한다.

## 종료 불변식 검사

`teardown()` 에서 미배정 목록을 다시 조회해 **경합에 쓴 업무가 모두 OPEN(소유자 없음)으로 돌아왔는지** 확인한다.

"성공 수령 수 == 성공 반납 수" 는 간접 증거일 뿐이라, 특정 시점에 소유자가 정확히 한 명이었는지를
직접 증명하지는 못한다. 종료 시점의 상태를 서버에 직접 물어 한 겹 더 확인한다.

## 결과 읽는 법

| 패널 | 봐야 할 것 |
| --- | --- |
| 수령 결과 구성 | `success` 1건 대 `conflict` 다수 → 조건부 UPDATE 가 단일 수령을 보장하고 있다는 증거 |
| 수령 API 응답 시간 | 경합이 커지면 p50 은 그대로인데 **p99 가 먼저 튄다.** 행 락 대기가 꼬리에만 얹히기 때문 |
| 수령 성공률 | VU 를 늘리거나 업무 수를 줄이면 떨어진다. 대략 `업무 수 / 시도 수` 에 수렴 |
| `limit_exceeded` | 반납이 밀려 기사가 한도(기본 3건)를 채운 상태 |
| `lock_conflict` | 락 대기 타임아웃. 정상 범위에서는 0 이어야 한다 |

## 결과 파일

실행이 끝나면 두 파일이 남는다. (`.gitignore` 처리되어 있다)

| 파일 | 내용 |
| --- | --- |
| `k6/summary.md` | 사람이 읽는 표 — 결과 구성, 처리량, p50/p95/p99, threshold 판정 |
| `k6/summary.json` | k6 원본 지표 전체 |

터미널 출력까지 통째로 남기려면:

```bash
k6 run k6/claim-contention.js 2>&1 | tee k6/run-$(date +%Y%m%d-%H%M).log
```

Grafana 패널 값이 필요하면 패널 우상단 `⋮` → `Inspect` → `Data` → `Download CSV`.

## 자주 나오는 신호 읽기

| 신호 | 의미 |
| --- | --- |
| `DELETE` 404 | 같은 기사 토큰을 쓰는 다른 VU 가 먼저 반납한 경우. **앱 오류가 아니라 스크립트 특성**이다. `DRIVERS` 를 VU 수 이상으로 두면 사라진다 (기본값이 VU 수와 같다) |
| `idempotent` | 같은 기사가 이미 가진 업무를 다시 요청. 멱등 계약이 지켜지고 있다는 뜻 |
| `limit_exceeded` 0 | 수령 즉시 반납하므로 한도에 도달하지 않는 게 정상. `SLEEP` 을 키우면 나타날 수 있다 |
| `lock_conflict` > 0 | 락 대기 타임아웃. VU 를 과하게 올렸거나 DB 가 느린 상태 |
| `unexpected` > 0 | 200/409 외 응답. threshold 가 깨지므로 반드시 원인을 봐야 한다 |

## 이 테스트가 검증하지 않는 것

부하 테스트 한 번으로 동시성 정책 전체가 검증됐다고 읽으면 안 된다.
아래 항목은 **JUnit 동시성 테스트(`DeliveryPlanClaimConcurrencyTest`)가 결정적으로 검증**한다.

| 항목 | 왜 이 부하 테스트로는 안 되는가 |
| --- | --- |
| 멱등성 | VU 와 기사가 1:1 이라 같은 기사의 중복 요청이 구조적으로 발생하지 않는다 (`idempotent` 0) |
| 동시 보유 한도 | 수령 즉시 반납하므로 한도에 도달하지 않는다 (`limit_exceeded` 0) |
| 60초 우선권 윈도우 전체 구간 | 성공자가 곧바로 반납하면 `public_at` 이 비워져 윈도우가 조기 종료된다 |
| 특정 시점의 단일 소유자 | 종료 시점 상태와 건수 일치는 강한 간접 증거지만 직접 증명은 아니다 |

또한 서버 CPU·JVM GC·HikariCP 풀·MySQL 락 대기 지표가 없어 **현재 처리량에서 병목이 어디인지는 알 수 없다.**
`constant-vus` 는 고정 부하이므로 서버의 최대 용량도 측정하지 않는다. 용량은 `PROFILE=stress` 로 따로 본다.

## 정리

부하 테스트가 만든 계정과 업무는 DB 에 남는다. 로컬이라면 전체 재생성이 가장 깔끔하다.

```bash
mysql -u root -p < src/main/resources/data.sql
```
