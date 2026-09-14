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

## 배송 기사 위치 및 관리자 통계 API

- `PUT /api/drivers/me/location`: 로그인한 배송 기사의 최신 위도·경도 갱신
- `GET /api/drivers/me/location`: 로그인한 배송 기사의 최신 위치 조회
- `GET /api/admin/drivers/locations`: 관리자의 전체 기사 최신 위치 조회
- `GET /api/admin/drivers/{driverId}/location`: 관리자의 특정 기사 최신 위치 조회
- `GET /api/admin/delivery-plans/statistics`: 계획·배송지·박스·위험 배송지 통계 조회

현재 단계에서는 기사별 최신 위치 한 건을 MySQL에 보관한다. 이후 갱신 주기가 짧아지면 Redis GEO와 WebSocket 또는 SSE를 추가해 실시간 전송 및 위치 이력을 확장할 수 있다.

기존 로컬 DB에는 [`docs/sql/20260914_add_driver_location.sql`](docs/sql/20260914_add_driver_location.sql)을 한 번 실행해 위치 테이블을 추가한다.
