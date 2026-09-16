# 배송 위험도 관리 서비스

Kotlin + Spring Boot 4 기반 배송 관리 서비스. 기상 정보로 배송지 위험도를 계산하고,
배치로 날씨를 갱신하며 오래된 완료 배송을 정리한다.

## 코드 컨벤션

아래는 이 저장소에서 지켜야 하는 규칙이다. 코드 리뷰 시 위반 여부를 확인한다.

### 엔티티

- JPA 엔티티는 `data class` 로 선언하지 않는다.
  `equals` 와 `hashCode` 가 모든 프로퍼티를 참조해 연관관계 프록시를 건드리기 때문이다.
- 엔티티의 주 생성자는 `private constructor` 로 막고, `companion object` 의 `of` 팩토리로만 생성한다.
- 외부에서 바꾸면 안 되는 엔티티 프로퍼티는 `protected set` 을 붙인다.
- JPA 와 검증 애너테이션은 생성자 파라미터에 `@field:` 접두사를 붙여 필드에 적용한다.
  접두사가 없으면 값 파라미터에만 붙어 동작하지 않는다.
- 컬렉션 연관관계는 `private var xxxEntities: MutableList<T>` 로 두고,
  외부에는 `val xxx: List<T>` 읽기 전용 프로퍼티로 노출한다. 가변 컬렉션을 그대로 반환하지 않는다.

### 널 처리

- `!!` 를 쓰지 않는다. 현재 `src/main` 에 `!!` 사용은 0건이며 이 상태를 유지한다.
  널일 수 있으면 `?.`, `?:`, `requireNotNull(...) { "설명" }` 중 하나를 쓴다.
- 엔티티 프로퍼티의 널 허용 여부는 `@field:Column(nullable = ...)` 과 일치시킨다.

### 쿼리

- 연관 엔티티를 함께 쓰는 조회는 `join fetch` 로 한 번에 가져온다. N+1 을 만들지 않는다.
- 반복문 안에서 리포지토리를 호출하지 않는다. ID 목록을 모아 `IN` 조회로 한 번에 처리한다.
- `findAll()` 로 전체를 읽은 뒤 메모리에서 거르지 않는다. 조건은 쿼리로 넘긴다.
- 벌크 삭제/수정 쿼리는 `cascade` 와 영속성 컨텍스트를 우회한다.
  연쇄 삭제가 필요하면 엔티티 단위 `delete` 를 쓴다.

### 트랜잭션

- `@Transactional` 을 `private` 메서드에 붙이지 않는다. 프록시가 가로채지 못해 무시된다.
- 같은 클래스 내부 호출에도 `@Transactional` 이 적용되지 않는다.

### 배치

- 배치 관련 빈에는 `@Profile("batch")` 를 붙인다. 웹 인스턴스에서 배치가 중복 실행되면 안 된다.
- 삭제 배치의 Reader 는 OFFSET 페이징을 쓰지 않는다.
  읽으면서 지우면 기준점이 밀려 행을 건너뛴다. 마지막 처리 ID 를 커서로 쓴다.
- 배치 주기는 코드에 하드코딩하지 않고 `application-batch.yaml` 의 설정값으로 둔다.

### 설정과 비밀값

- API 키, 비밀번호, 토큰을 소스나 설정 파일에 직접 적지 않는다.
  `${ENV_VAR}` 형태로 환경 변수에서 읽는다.
- `src/main/resources/data.sql` 은 테이블을 DROP 후 재생성한다.
  이 파일을 실행하는 설정(`spring.sql.init.mode`)을 켤 때는 데이터 손실을 확인한다.
- 임시 테스트 데이터를 `data.sql` 에 남겨두지 않는다.

### 로그

- 로그 메시지는 한국어로 쓴다.
- 로그에 API 키, 토큰, 비밀번호를 출력하지 않는다.

## 빌드와 실행

```bash
./gradlew build                                              # 빌드와 테스트
./gradlew bootRun --args='--spring.profiles.active=web'      # API 서버 (8080)
./gradlew bootRun --args='--spring.profiles.active=batch'    # 배치 서버 (8081)
```

DB 가 필요한 통합 테스트는 `TEST_DB_URL` 환경 변수가 있을 때만 실행된 다.
