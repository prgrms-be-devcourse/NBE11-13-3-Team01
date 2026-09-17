# 네이티브 경로 최적화 — 폴리글랏 실험

배송 경로 탐색(`RouteOptimizer`)을 C 로도 구현하고 FFM(`java.lang.foreign`)으로 호출한다.

## C 최적화 서버

`route_optimizer_server`는 같은 bitmask DP를 HTTP 서비스로 제공한다. Spring은
`POST /optimize`에 `nodeCount`와 row-major `costs`를 보내고, C가 반환한 경로를
우선 사용한다. 응답 오류·timeout·연결 실패가 발생하면 Kotlin의
`DijkstraRouteOptimizer`로 fallback한다. 두 구현도 실패하면 기존 서비스 정책에 따라
경로 추천 없이 다음 흐름을 계속한다.

```bash
make server
ROUTE_OPTIMIZER_PORT=8091 ./build/route_optimizer_server
# UDP 비교용
make udp-server
ROUTE_OPTIMIZER_UDP_PORT=8092 ./build/route_optimizer_udp_server
```

프로젝트 루트의 Docker Compose에서도 실행할 수 있다.

```bash
docker compose up -d route-optimizer
./gradlew bootRun
```

Spring 원격 호출은 기본 활성화되며 기본 transport는 UDP(`localhost:8092`)다.
HTTP 비교 시 `ROUTE_OPTIMIZER_REMOTE_TRANSPORT=http`를 지정하면
`localhost:8091`로 요청을 보낸다. UDP에서 응답이 없거나 손상되면 Kotlin fallback을 사용한다.
필요하면 `ROUTE_OPTIMIZER_REMOTE_ENABLED`, 주소·포트 환경변수로 설정을 재정의할 수 있다.
그 외 설정은
`ROUTE_OPTIMIZER_REMOTE_CONNECT_TIMEOUT`(기본 200ms),
`ROUTE_OPTIMIZER_REMOTE_READ_TIMEOUT`(기본 500ms) 환경변수로 조정한다.

간단한 요청 형식은 다음과 같다.

```json
{"nodeCount":3,"costs":[-1,1,2,-1,-1,3,-1,-1,-1]}
```

응답의 `status=0`이고 `route`가 모든 후보를 정확히 한 번씩 포함할 때만 사용한다.

로그에는 계산 출처가 구분되어 남는다.

- `source=c-route-optimizer`: C 서버 응답을 사용함
- `source=kotlin-dijkstra`: C 호출 실패 후 Kotlin fallback 또는 원격 기능 비활성 상태

**운영 최적화가 아니다.** 후보가 5개인 현재 규모에서 DP 자체는 수십 마이크로초이고,
추천 API 전체 시간은 JPA 조회와 Kakao 길찾기 호출이 지배한다. 이 실험의 목적은
언어 경계·ABI·네이티브 메모리 수명을 직접 다뤄 보고, **후보 수가 몇 개부터 뒤집히는지**를
숫자로 확인하는 것이다.

## 구현이 셋인 이유

| | 클래스 | 역할 |
| --- | --- | --- |
| A | `DijkstraRouteOptimizer` | 운영 기본 구현. 상태 우선순위 큐 + `Set<Long>` 할당 |
| B | `BitmaskDpRouteOptimizer` | **같은 알고리즘을 JVM 에서.** 평탄 배열 DP |
| C | `NativeRouteOptimizer` → `libroute_optimizer` | 같은 알고리즘을 C 로 |

A 와 C 를 바로 비교하면 차이의 대부분이 **언어가 아니라 자료구조**에서 온다.
우선순위 큐와 `Set` 할당을 평탄 배열로 바꾼 효과가 네이티브의 공으로 잡히기 때문이다.
B 를 사이에 두어야 `B ↔ C` 가 비로소 언어와 FFI 경계 비용만 재게 된다.
실제로 B 가 C 를 이기는 구간이 꽤 넓게 나올 것으로 예상되고, 그것 자체가 결론이다.

## 동점 처리 계약

최소 비용 경로가 여러 개일 때 **노드 번호 수열이 사전순으로 가장 작은 것**을 돌려준다.
B 와 C 가 이 계약을 공유하므로 differential test 가 총비용뿐 아니라 **경로 순서까지** 비교한다.

A 에는 이 계약이 **없다.** `PriorityQueue` 는 동점 원소의 순서를 보장하지 않고,
현재 코드의 `>=` 조건은 먼저 발견한 경로를 유지할 뿐이다. 같은 입력에서 재현되기는 하지만
그건 후보 목록 순회 순서가 고정돼 있어 생기는 결과이지 계약이 아니다.
그래서 A 는 총비용만 비교하고, 돌려준 경로가 **실제로 유효한 최적 경로인지**를 따로 검증한다.

DP 를 앞에서부터 채우고 parent 포인터로 되짚으면 "최소 비용 경로 중 하나"만 나오고
그게 어느 것인지는 갱신 순서에 달린다. 그래서 B·C 모두 뒤에서부터
`remaining[mask][last]`(남은 후보를 전부 방문하는 최소 추가 비용)를 채운 뒤,
앞에서부터 걸으며 최적을 유지하는 후보 중 번호가 가장 작은 것을 고른다.

## ABI 계약

`route_optimizer.h` 가 계약 문서다. 특히:

- **노드 번호를 다시 매긴다.** 실제 stop id 에는 `Long.MIN_VALUE`(출발지 노드)가 섞여 있어
  배열 첨자로 쓸 수 없다. `0 = 현재 위치`, `1..n = 후보(주어진 순서)`.
- **간선 없음은 음수(-1).** `INT64_MAX` 를 sentinel 로 쓰면 더하는 순간 오버플로가 난다.
  `TravelCostMatrix` 가 음수 이동시간을 이미 금지하므로 -1 은 안전하게 비어 있는 값이다.
- **오버플로는 `__builtin_add_overflow`** 로 잡아 `ROUTE_STATUS_OVERFLOW` 를 돌려준다.
  Kotlin 의 `Math.addExact` 와 같은 자리에서 잡히고, 세 구현 모두 `ArithmeticException` 으로 끝난다.
- **`reachable_states` 는 `expandedStateCount` 와 정의가 다르다.** 성능 관찰용이며
  구현 간 동등성 비교 대상이 아니다.
- 상한은 후보 16개(`ROUTE_MAX_NODE_COUNT = 17`). `RouteMatrix.MAX_NODE_COUNT` 와 같아야 한다.

## 바인딩만 Java 인 이유

`MethodHandle.invokeExact` 는 signature-polymorphic 메서드다. 호출부에 적힌 정적 타입이
그대로 호출 시그니처가 되고 JIT 이 직접 호출로 낮춘다. **Kotlin 은 signature-polymorphic
호출부를 만들지 못한다.** Kotlin 에서 쓰면 `WrongMethodTypeException` 이 나거나,
우회하려고 `invokeWithArguments` 를 쓰면 인자마다 박싱이 생긴다.

이 프로젝트의 목적이 FFI 경계 비용 측정이므로 측정 대상에 박싱이 섞이면 실험이 무의미해진다.
그래서 `RouteOptimizerNative.java` 한 겹만 Java 다.

## Arena 수명

- 라이브러리 조회: `Arena.global()` — 프로세스가 끝날 때까지 매핑을 유지한다.
- 호출당 버퍼: `Arena.ofConfined()` — try-with-resources 로 즉시 해제한다.

이 **호출당 할당과 복사가 곧 FFI 오버헤드의 대부분**이고, 벤치마크가 드러내야 하는 값이다.

## 실행

```bash
# C 구현을 완전탐색과 대조 (후보 7개까지 전 순열)
make test
./gradlew testNativeRouteOptimizer

# ASan/UBSan 으로 같은 테스트
make sanitize
./gradlew sanitizeNativeRouteOptimizer

# 공유 라이브러리 빌드
make
./gradlew buildNativeRouteOptimizer

# 세 구현 differential test (네이티브가 없으면 해당 테스트만 건너뛴다)
./gradlew test --tests '*RouteOptimizerDifferentialTest*'

# crossover point 측정
./gradlew buildNativeRouteOptimizer benchmarkRouteOptimizer

# TCP/HTTP와 UDP 네트워크 왕복 비교 (두 서버를 먼저 실행)
node native/route_optimizer/benchmark_transport.mjs
```

벤치마크는 동일한 6개 노드 입력을 순차 요청하고 평균, p50, p95, p99, 최대 지연과 실패 수를 출력한다.
이는 알고리즘 실행 시간이 아니라 애플리케이션에서 측정한 네트워크 왕복 시간이다.

라이브러리 위치는 `route.optimizer.native.library` 시스템 프로퍼티나
`ROUTE_OPTIMIZER_NATIVE_LIBRARY` 환경변수로 덮어쓸 수 있다. 기본값은
`native/route_optimizer/build/libroute_optimizer.{dylib|so}` (작업 디렉터리 기준)이다.

**라이브러리가 없어도 애플리케이션은 뜬다.** `RouteOptimizerNative.isAvailable()` 이 false 면
`NativeRouteOptimizer` 가 Kotlin 구현으로 폴백한다. 그래서 네이티브 빌드를 기본 `build` 에 걸지 않았다.

## Spring 에 붙이지 않은 이유

`DijkstraRouteOptimizer` 만 `@Component` 다. B 와 C 를 빈으로 등록하면
`RouteOptimizer` 주입이 모호해져 애플리케이션이 뜨지 않는다. 운영 구현을 바꾸는 것은
측정 결과를 본 뒤에 할 일이므로, 지금은 테스트와 벤치마크에서만 직접 생성해 쓴다.

## 아직 안 한 것

- **JMH 가 아니다.** Gradle 9.5.1 과 맞는 JMH 플러그인 조합을 확인하지 못해
  직접 돌리는 하네스로 두었다. fork 분리·프로파일러·신뢰구간이 없으므로
  "몇 % 빠르다"의 근거로는 약하다. crossover 위치를 보는 용도로만 쓴다.
- 라이브러리를 jar 리소스로 묶어 배포하는 처리. 지금은 파일 경로로만 찾는다.
- Windows(`route_optimizer.dll`) 빌드는 이름만 준비돼 있고 검증하지 않았다.
