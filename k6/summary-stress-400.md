# 배송 업무 수령 경합 부하 테스트 결과

- 실행 시각: 2026-09-14T07:06:14.183Z
- 부하 시간: 298.7초
- 대상: http://localhost:8080
- 프로필: stress
- VU 유지 구간: 60 → 140 → 280 → 400 (각 45초 유지, peak 400) / 기사 계정: 400 / 경합 업무: 3

## 수령 결과

| 결과 | 건수 | 비율 |
| --- | ---: | ---: |
| 성공 | 27939 | 12.17% |
| 멱등(이미 본인 소유) | 0 | 0.00% |
| 경합 충돌 | 201709 | 87.83% |
| 보유 한도 초과 | 0 | 0.00% |
| 우선권 차단 | 11 | 0.00% |
| 락 경합 | 0 | 0.00% |
| 예상 밖 응답 | 0 | 0.00% |
| **합계** | **229659** | |

- 수령 시도 처리량: 900.6 건/초 (부하 구간 255초 기준)
- 전체 실행 시간: 298.7초 (setup 포함)
- 완료 반복: 229659회

## 응답 시간

| 경로 | p50 | p90 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| 수령(claim) | - | - | - | - | - |
| 반납(release) | - | - | - | - | - |

## 반납

- 성공: 27939
- 이미 반납됨(404): 0
- 실패: 0

## VU 유지 구간별 지연 (무릎점 탐색)

각 구간을 동일하게 45초씩 유지한 표본만 집계했다. ramp 상승·하강 구간은 제외했다.

| 유지 VU | 수령 시도 | 시도/초 | p50 | p90 | p95 | p99 | max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 60 | 11845 | 263.2 | 21.9ms | 37.3ms | 47.4ms | 105.7ms | 285.1ms |
| 140 | 28440 | 632.0 | 8.8ms | 35.3ms | 58.3ms | 162.8ms | 552.4ms |
| 280 | 55245 | 1227.7 | 8.6ms | 55.9ms | 106.1ms | 309.5ms | 615.4ms |
| 400 | 75782 | 1684.0 | 15.4ms | 91.2ms | 142.4ms | 256.0ms | 741.9ms |

> VU 가 2배가 될 때 **시도/초가 2배로 늘지 않기 시작하는 구간**이 포화 후보다.
> 같은 시점에 p95·p99 가 함께 꺾이는지 확인한다. 원인 판별은 서버 리소스 지표와 함께 봐야 한다.

## 종료 불변식

- 모든 경합 업무가 OPEN(소유자 없음) 으로 복귀했다.

## 판정

- PASS `claim_attempts{plateau:0140}` count>=0
- PASS `release_already_returned` count==0
- PASS `claim_attempts{plateau:0280}` count>=0
- PASS `http_req_duration{endpoint:claim,plateau:0140}` p(95)>=0
- PASS `claim_unexpected` count==0
- PASS `claim_attempts{plateau:0060}` count>=0
- PASS `http_req_duration{endpoint:claim,plateau:0400}` p(95)>=0
- PASS `release_failed` count==0
- PASS `http_req_duration{endpoint:claim,plateau:0060}` p(95)>=0
- PASS `http_req_duration{endpoint:claim,plateau:0280}` p(95)>=0
- PASS `claim_attempts{plateau:0400}` count>=0
