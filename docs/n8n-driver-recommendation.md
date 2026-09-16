# n8n 배송 기사 추천 연동 계약

## 처리 흐름

1. 백엔드가 역할과 보유 한도로 수령 불가능한 기사를 제외한다.
2. 결정적 스코어러가 거리, 업무량, 남은 배송지·박스, 위험 배송지, 위치 신선도로 후보 풀을 만든다.
3. 트랜잭션 밖에서 n8n Webhook을 호출한다.
4. n8n은 관리자 조회에서는 결정적 상위 N명에 대한 근거를 만들고, 우선권 등록에서는 전달받은 후보 안에서 우선권 대상·순위와 0~100점·한국어 근거를 반환한다.
5. 백엔드는 응답 전체를 검증하고, 저장 직전에 기사 행을 다시 잠가 자격과 보유 한도를 확인한다.
6. 성공하면 60초 우선권을 저장한다. 어느 단계든 실패하면 `public_at=NULL`로 즉시 전체 공개한다.

외부 호출 제한 시간은 기본적으로 연결 500ms, 전체 응답 2초다. n8n 재시도는 Webhook 응답 전에 길게 수행하지 않는다.

## 인증

백엔드는 다음 헤더를 보낸다.

```http
Content-Type: application/json
X-N8N-Secret: <DELIVERY_AI_RECOMMENDATION_SECRET>
```

n8n의 첫 노드에서 `X-N8N-Secret`을 고정 credential과 비교하고 불일치하면 401 또는 403으로 끝낸다.
secret과 전체 요청 payload는 실행 로그에 남기지 않는다.

## 요청 예시

```json
{
  "requestId": "99f1db0d-7d68-45f0-b1f8-5a1d178edc27",
  "evaluatedAt": "2026-09-16T12:00:00",
  "target": {
    "scheduledDepartureAt": "2026-09-16T13:00:00",
    "totalStops": 5,
    "totalBoxes": 24,
    "dangerStops": 1
  },
  "requestedDriverCount": 3,
  "candidates": [
    {
      "driverId": 7,
      "baseScore": 82,
      "distanceMeters": 1840,
      "estimatedTravelSeconds": 410,
      "locationFresh": true,
      "activePlans": 1,
      "remainingStops": 3,
      "remainingBoxes": 12,
      "dangerStops": 0,
      "features": [
        { "feature": "distance", "normalized": 0.94, "contribution": 37.6 }
      ]
    }
  ]
}
```

기사 이름·로그인 ID와 원본 위도·경도, 출발지 주소는 포함하지 않는다. n8n은 `driverId`를 식별자처럼만 사용하고
요청 후보에 없는 ID를 새로 만들면 안 된다.

## 정상 응답 예시

```json
{
  "requestId": "99f1db0d-7d68-45f0-b1f8-5a1d178edc27",
  "recommendations": [
    {
      "driverId": 7,
      "suitabilityScore": 91,
      "reasons": [
        "출발지 접근 시간이 짧고 현재 업무량이 낮습니다.",
        "위치 정보가 최신이어서 도착 시간 추정의 신뢰도가 높습니다."
      ]
    }
  ]
}
```

응답 규칙은 다음과 같다.

- `requestId`는 요청과 정확히 같아야 한다.
- `recommendations` 수는 `requestedDriverCount`와 같아야 한다.
- `driverId`는 요청 후보 안에서 중복 없이 선택한다.
- `suitabilityScore`는 0~100 정수다.
- `reasons`는 기사당 1~3개, 각 200자 이하의 비어 있지 않은 문자열이다.
- JSON 외 텍스트나 Markdown code fence를 섞지 않는다.

관리자 조회 요청은 결정적 상위 N명만 후보로 전달되므로 n8n 응답 순서와 점수는 무시되고 근거만 병합된다.
우선권 등록 요청은 `candidate-pool-size`명까지 전달될 수 있으며, 이때는 응답 순서와 점수가 우선권 대상 선정에 사용된다.

일부만 유효한 응답도 전체를 폐기한다. 부분 우선권을 허용하면 n8n 응답 형식 오류가 특정 기사에게만
불공정한 선공개로 이어질 수 있기 때문이다.

## 권장 n8n 노드 순서

`Webhook → Secret 검증 → 입력 스키마 검증 → AI/규칙 노드 → 출력 정규화·검증 → Respond to Webhook`

AI 프롬프트에는 요청 JSON, 위 응답 스키마, 후보 밖 ID 금지, 정확한 추천 수를 명시한다.
마지막 Code 노드에서도 점수 범위·ID 중복·추천 수를 검사한 뒤 응답해야 한다. 백엔드가 동일 검증을 다시 수행하므로
n8n 검증은 빠른 오류 탐지용이고 최종 신뢰 경계는 백엔드다.

## 관측

- `delivery_ai_recommendation_total{result=...}`: n8n HTTP 경계의 실패 유형
- `delivery_ai_recommendation_decision_total{result=...}`: 최종 AI 적용 여부와 응답 계약 검증 결과
- `delivery_priority_window_total{result="opened|fail_open",reason=...}`: 우선권 생성 또는 즉시 전체 공개 결과

운영 알림은 timeout/http_error/invalid_response 비율과 `fail_open` 증가를 보되, 이 장애는 업무 등록 실패가 아니라
전체 공개 전환으로 나타난다는 점을 함께 표시한다.
