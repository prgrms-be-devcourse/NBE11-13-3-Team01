## 백엔드 개발

백엔드 운영 코드와 테스트는 모두 Kotlin입니다. JDK 25와 Gradle Wrapper를 사용합니다.

빌드 구성과 코틀린 설계 원칙, 검증 명령은 [Kotlin 마이그레이션 노트](docs/kotlin-migration.md)를 참고하세요.

## 테스트 계정

### 관리자

- ID: `admin`
- Password: `1234`

### 배송 기사

- `user1` / `1234` / 기사1
- `user2` / `1234` / 기사2
- `user3` / `1234` / 기사3

> 로컬 개발 및 테스트용 계정입니다.

## 배송 업무 선착순 수령 (동시성 제어)

관리자가 기사를 지정해 할당하는 대신, 기사를 비워 둔 `OPEN` 상태로 업무를 등록하면
배송 기사들이 목록에서 확인하고 직접 가져가는 방식이다.

배송 계획 상태는 `OPEN → READY → DELIVERING → COMPLETED` 순으로 전이한다.

### API

- `POST /api/admin/delivery-plans`: 관리자의 미배정(OPEN) 배송 업무 등록
- `POST /api/admin/drivers/{driverId}/delivery-plans`: 관리자의 직접 할당 (기존 경로 유지)
- `GET /api/delivery-plans/open`: 기사의 미배정 업무 목록 조회 (본인 우선권 순위·공개 시각 포함)
- `POST /api/delivery-plans/{planId}/claim`: 기사의 업무 수령
- `DELETE /api/delivery-plans/{planId}/claim`: 배송 시작 전 업무 반납

### 우선 수령 윈도우 (차등 공개)

선착순만 두면 알림을 먼저 본 기사가 가져가고, 관리자가 직접 배정하면 기사의 선택권이 사라진다.
그 사이를 메우기 위해 **등록 직후 짧은 구간 동안 추천 상위 N명에게만 수령 권한을 연다.**
아무도 가져가지 않으면 공개 시각 이후 전체가 선착순으로 경쟁한다. 강제 배정은 하지 않는다.

```
등록  ├─ 추천 상위 3명만 수령 가능 ─┤─ 전체 기사 선착순 ─────────▶
      0s                        60s (public_at)
```

- `delivery_plan.public_at`: 전체 공개 시각. NULL 이면 처음부터 전체 공개
- `delivery_plan_priority_driver`: 공개 전까지 수령할 수 있는 추천 상위 기사와 순위·점수

우선권 대상은 등록 트랜잭션 안에서 **추천 API 와 동일한 스코어러**로 정한다.
`DriverCandidateLoader` 로 후보 조립을 공유하므로 "추천 1위인데 우선권이 없다" 같은 모순이 생기지 않는다.

#### 판정을 DB 로 내린 이유

우선권 검사를 애플리케이션에서 먼저 읽고 판단하면, 그 사이에 공개 시각이 지나거나
다른 기사가 수령해 버리는 틈이 생긴다. 그래서 공개 여부와 우선권 보유 여부를
수령 UPDATE 의 조건에 함께 실어 윈도우 경계의 경합까지 DB 가 한 번에 판정하게 했다.

```sql
UPDATE delivery_plan
SET driver_id = ?, status = 'READY', assigned_at = ?, version = version + 1
WHERE id = ? AND status = 'OPEN' AND driver_id IS NULL
  AND (
        public_at IS NULL
        OR public_at <= ?
        OR EXISTS (SELECT 1 FROM delivery_plan_priority_driver pd
                   WHERE pd.delivery_plan_id = ? AND pd.driver_id = ?)
      )
```

갱신 대상과 다른 테이블을 참조하는 서브쿼리라 MySQL 의
"같은 테이블을 갱신하며 서브쿼리로 조회할 수 없다" 제약에는 걸리지 않는다.

서비스 계층의 사전 검사는 **빠른 실패와 정확한 사유**를 위한 것이고, 보장은 위 UPDATE 가 한다.
0행이 돌아오면 최신 상태를 다시 읽어 `이미 수령됨(409)` / `우선 수령 구간(409)` / `본인 소유(멱등 200)` 를 구분한다.

#### 정책

- 목록에서 **숨기지 않는다.** 우선권이 없는 기사에게도 업무를 보여주되 `claimableNow=false` 와
  `publicAt` 을 함께 내린다. 목록에서 사라졌다가 공개 시각에 갑자기 나타나면 기회를 놓치기 때문이다.
- **반납한 업무는 우선권을 다시 열지 않는다.** (`public_at = NULL`)
  추천 상위 기사가 수령·반납을 반복하며 같은 업무를 계속 선점하는 것을 막는다.
- 수령 가능한 기사가 없거나 `delivery.priority-window.enabled=false` 면 즉시 전체 공개한다.
- 메트릭에 `result="priority_blocked"` 태그가 추가되어 윈도우가 실제로 경쟁을 얼마나 지연시키는지 관측할 수 있다.

### 화면

| 화면 | 경로 | 역할 |
| --- | --- | --- |
| 업무 가져가기 | `/market` (기사) | 미배정 업무 카드 목록, 우선권 순위 배지, 공개까지 남은 시간 카운트다운, 가져오기 |
| 배송 계획 상세 | `/plans/:planId` | 기사: READY 상태에서 업무 반납 / 관리자: 기사 추천 패널(점수·피처 기여도·근거) |
| 업무 등록 | `/plans/new` (관리자) | 미배정으로 공개 / 특정 기사 직접 할당 선택 |

업무 마켓은 5초마다 목록을 다시 불러오고, 카운트다운은 1초마다 갱신한다.
우선권이 없어 아직 가져갈 수 없는 업무는 목록에서 숨기지 않고 점선 카드로 낮춰 표시한다.

수령 실패는 서버 에러 코드별로 다른 문구를 보여준다.
"이미 다른 기사가 가져감 / 보유 한도 초과 / 우선 수령 구간 / 락 경합"은 사용자가 취해야 할 행동이 각각 다르기 때문이다.
경합에서 밀린 경우에는 목록을 즉시 다시 불러와 화면을 최신 상태로 맞춘다.

### 동시성 제어 방식

여러 기사가 같은 업무를 동시에 수령하려는 경합은 두 층으로 나눠 처리한다.

1. **계획 행 - 원자적 조건부 UPDATE** (`DeliveryPlanRepository.claimIfOpen`)

   ```sql
   UPDATE delivery_plan
   SET driver_id = ?, status = 'READY', assigned_at = ?, version = version + 1
   WHERE id = ? AND status = 'OPEN' AND driver_id IS NULL
   ```

   조건을 UPDATE 문에 함께 실어 보내 InnoDB 가 행을 잠근 상태에서 재평가하게 만든다.
   경합한 요청 중 한 건만 `affected rows = 1` 을 받고 나머지는 `0` 을 받아 409 로 응답한다.
   조회-검증-저장이 분리되지 않으므로 **read-modify-write 윈도우 자체가 없어** lost update 가 발생하지 않는다.

   다만 이 UPDATE 도 일반적인 행 락을 잡는다. 다른 트랜잭션이 같은 행을 갱신하고 아직 커밋하지 않았다면
   **락이 풀릴 때까지 대기한 뒤** 조건을 재평가한다. 즉 "즉시 실패"가 아니라 "짧게 대기 후 판정"이다.
   대기가 `innodb_lock_wait_timeout` 을 넘기면 `PessimisticLockingFailureException` 이 발생하는데,
   `GlobalExceptionHandler` 가 이를 500 이 아닌 409(`DELIVERY_CLAIM_LOCK_CONFLICT`)로 변환해
   클라이언트가 재시도 가능한 상태임을 알 수 있게 한다.

2. **기사 행 - 비관적 락** (`UserRepository.findUserByIdForUpdate`)

   1번은 "한 업무를 한 기사만 가져간다"까지만 보장하고,
   "한 기사가 동시 보유 한도를 넘기지 않는다"는 보장하지 못한다.
   같은 기사의 요청을 `users` 행 락으로 직렬화해 보유 수량 검사와 수령을 원자적으로 만든다.
   데드락을 피하기 위해 수령 경로에서는 항상 `users → delivery_plan` 순서로만 잠근다.

3. **트랜잭션 격리 수준 - `READ_COMMITTED`**

   MySQL 기본값인 `REPEATABLE READ` 에서는 트랜잭션의 첫 consistent read(일반 SELECT)가 스냅샷을 만들고
   이후의 일반 SELECT 가 모두 그 스냅샷을 재사용한다.
   그래서 기사 행 락을 기다렸다가 획득하더라도 그 뒤의 보유 수 COUNT 는
   기다리는 동안 커밋된 다른 트랜잭션의 결과를 보지 못하고 낡은 값을 돌려준다.

   > 활성 2건 / 한도 3건인 기사가 서로 다른 두 업무를 동시에 claim →
   > 두 트랜잭션 모두 "2건" 스냅샷을 읽음 → 양쪽 다 통과 → 최종 4건 (한도 위반)

   이를 막기 위해 수령·반납·직접 할당 트랜잭션만 `READ_COMMITTED` 로 실행해
   매 SELECT 가 최신 커밋 상태를 읽게 한다. 부수적으로 갭 락이 사라져 데드락 가능성도 줄어든다.
   또한 기사 행 잠금을 트랜잭션의 첫 DB 접근으로 두어 이후 조회가 항상 락 획득 이후 상태를 읽도록 한다.

엔티티의 `@Version` 은 수령 경로 밖에서 계획이 수정될 때를 위한 도메인 차원의 2차 방어선이다.

### 정책

- **멱등성**: 이미 본인이 수령한 업무를 다시 요청하면 409 가 아니라 `alreadyOwned=true` 로 200 을 반환한다.
  동시 중복 요청(더블 클릭, 네트워크 재시도)도 기사 행 락으로 직렬화된 뒤 소유권을 재확인하므로
  두 요청 모두 200 을 받고 그중 하나만 `alreadyOwned=false` 가 된다.
- **동시 보유 한도**: `delivery.claim.max-active-plans` (기본 3건). 초과 시 409.
  **관리자 직접 할당(`POST /api/admin/drivers/{driverId}/delivery-plans`)에도 동일하게 적용된다.**
  기사는 한도에 걸려 수령하지 못하는데 관리자 할당으로는 초과되는 모순을 막기 위해서다.
  관리자에게 한도 무시 권한이 필요해지면 별도의 명시적 정책(예: 요청 파라미터 + 감사 로그)으로 추가한다.
- **반납**: 배송을 시작하기 전(`READY`)에만 가능하다. `DELIVERING` 이후에는 400.

### 메트릭

Actuator/Prometheus 로 수령 경합 상황을 관측할 수 있다.

- `delivery_plan_claim_total{result="success|idempotent|conflict|limit_exceeded"}`
- `delivery_plan_release_total`

### 테스트

| 테스트 | 검증 대상 |
| --- | --- |
| `DeliveryPlanClaimServiceTest` | 수령 분기(성공·멱등·충돌·한도)와 **기사 락이 계획 조회보다 먼저 수행되는 호출 순서** |
| `DeliveryPlanClaimConcurrencyTest` | 실제 MySQL 경합 — 단일 수령, 보유 한도, 중복 요청 멱등, version 증가, nullable 프로젝션, 우선권 윈도우 차단·공개 후 경쟁 |
| `ScoreBasedDriverRecommenderTest` | 신선도 경계(30:00 / 30:01 / 30:59 / 미래 시각)와 stale 좌표의 정렬 제외 |
| `PriorityWindowAssignerTest` | 우선권 대상·순위 저장과 공개 시각 계산, 비활성·후보 없음 처리 |

동시성 테스트는 `@Transactional(propagation = NOT_SUPPORTED)` 로 테스트 래핑 트랜잭션을 끈다.
메서드 전체를 트랜잭션으로 감싸면 스레드가 커밋되지 않은 데이터를 보지 못해 경합 자체가 재현되지 않기 때문이다.
준비 데이터는 리포지토리 호출로 즉시 커밋되고, 각 스레드는 서비스의 `@Transactional` 로 독립 트랜잭션을 갖는다.
롤백이 없으므로 `@AfterEach` 에서 직접 정리한다.

H2 로는 InnoDB 행 잠금을 재현할 수 없어 실제 MySQL 이 필요하다. `TEST_DB_URL` 이 없으면 skip 된다.

```bash
TEST_DB_URL="jdbc:mysql://localhost:3306/delivery_service_test?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" \
DB_PASSWORD=... ./gradlew test
```

### 부하 테스트

선착순 수령의 경합률과 응답 시간을 실제 HTTP 부하로 관측하는 k6 시나리오가 있다.
수령에 성공한 VU 가 곧바로 반납해 업무를 풀에 되돌리므로, 적은 수의 업무로 지속적인 경합을 만든다.

```bash
DELIVERY_PRIORITY_WINDOW_ENABLED=false ./gradlew bootRun
k6 run k6/claim-contention.js
```

Grafana(http://localhost:3000)의 **배송 업무 수령 경합** 대시보드에서
`success` 1건 대 `conflict` 다수의 비율과 p95·p99 지연이 실시간으로 그려진다.
자세한 실행 옵션과 해석은 [`k6/README.md`](k6/README.md) 참고.

## 배송 기사 추천

- `GET /api/admin/delivery-plans/{planId}/driver-recommendations?limit=3`

`OPEN` 또는 `READY` 상태의 업무에 적합한 기사를 0~100 점으로 랭킹한다.
추천은 **배정이 아니라 참고용 랭킹**이며, 실제 수령은 여전히 기사가 직접 `claim` 한다.

피처는 모두 "높을수록 좋은" 0.0~1.0 값으로 정규화한 뒤 가중 평균한다.

| 피처 | 가중치 | 정규화 방식 |
| --- | --- | --- |
| 출발지까지 거리 | 0.40 | 설정 상한(`max-distance-meters`) 대비 절대 정규화 |
| 진행 중 업무 수 | 0.20 | 후보 중 최댓값 대비 상대 정규화 |
| 남은 배송지 | 0.15 | 후보 중 최댓값 대비 상대 정규화 |
| 남은 박스 | 0.10 | 후보 중 최댓값 대비 상대 정규화 |
| 보유 중인 위험 배송지 | 0.05 | 후보 중 최댓값 대비 상대 정규화 |
| 위치 정보 신선도 | 0.10 | `location-stale-minutes` 이내면 1.0, 아니면 0.0 |

신선도 판정은 `Duration` 으로 직접 비교한다. `toMinutes()` 로 절삭하면 임계값이 30분일 때
30분 59초까지 fresh 로 판정되어 설정 의미와 최대 59초 어긋나기 때문이다.
기기 시계 오차로 갱신 시각이 미래로 들어올 수 있어 경과 시간은 절댓값으로 비교한다.

업무량 피처에 상대 정규화를 쓰는 이유는 전체 기사의 업무량 수준이 시간대마다 달라지기 때문이다.
절대 임계값을 고정하면 모두가 바쁜 시간대에 전원이 0점이 되어 변별력이 사라진다.

위치 정보가 없거나 `location-stale-minutes` 를 넘겼으면 거리 점수를 중립값(0.5)으로 두고
신선도 피처에서만 감점한다. 오래된 좌표로 거리를 단정하지 않기 위해서다.
동점 정렬에서도 같은 원칙을 지켜, 신뢰할 수 없는 좌표는 정렬 기준에서 제외하고 기사 ID 로 순서를 확정한다.

응답에는 피처별 기여도(`featureScores`)와 한국어 근거 문구(`reasons`)가 함께 담겨
추천 결과를 설명할 수 있다. 동시 보유 한도를 넘긴 기사는 애초에 수령할 수 없으므로 후보에서 제외된다.

추천기는 `DriverRecommender` 인터페이스로 분리되어 있다.
LLM 기반 구현을 추가하더라도 **후보 랭킹은 결정적인 스코어러가 맡고 LLM 은 설명 생성만 담당**하도록 해,
배정 자체가 비결정적 요소에 의존하지 않게 한다.

## 배송 기사 위치 및 관리자 통계 API

- `PUT /api/drivers/me/location`: 로그인한 배송 기사의 최신 위도·경도 갱신
- `GET /api/drivers/me/location`: 로그인한 배송 기사의 최신 위치 조회
- `GET /api/admin/drivers/locations`: 관리자의 전체 기사 최신 위치 조회
- `GET /api/admin/drivers/{driverId}/location`: 관리자의 특정 기사 최신 위치 조회
- `GET /api/admin/delivery-plans/statistics`: 계획·배송지·박스·위험 배송지 통계 조회

현재 단계에서는 기사별 최신 위치 한 건을 MySQL에 보관한다. 이후 갱신 주기가 짧아지면 Redis GEO와 WebSocket 또는 SSE를 추가해 실시간 전송 및 위치 이력을 확장할 수 있다.

## DB 준비

`src/main/resources/data.sql` 하나에 최신 전체 스키마와 테스트용 샘플 데이터가 모두 들어 있다.
기사 위치, 배송 업무 수령(`driver_id` NULL 허용 · `assigned_at` · `version`),
우선 수령 윈도우(`public_at` · `delivery_plan_priority_driver`)까지 반영되어 있으므로
**새로 세팅할 때는 이 파일만 한 번 실행하면 된다.**

```bash
mysql -u root -p < src/main/resources/data.sql
```

> 이 스크립트는 DROP 후 CREATE 하므로 기존 데이터가 모두 사라진다. 로컬·테스트 DB 전용이다.

`docs/sql/` 의 날짜별 파일은 **데이터를 유지한 채 기존 DB를 올릴 때만** 쓰는 증분 마이그레이션이다.
`data.sql` 로 새로 만들었다면 실행할 필요가 없다.

| 파일 | 내용 |
| --- | --- |
| `20260914_add_driver_location.sql` | 기사 위치 테이블 |
| `20260915_add_delivery_plan_claim.sql` | `driver_id` NULL 허용, `assigned_at`, `version`, 인덱스 |
| `20260916_add_priority_claim_window.sql` | `public_at`, 우선권 테이블 |

동시성 테스트용 DB는 스키마를 엔티티에서 자동 생성(`ddl-auto: create-drop`)하므로 빈 DB만 있으면 된다.

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS delivery_service_test DEFAULT CHARACTER SET utf8mb4;"
```
