# 백엔드 Kotlin 마이그레이션

## 전환 범위

`src/main`의 애플리케이션, 설정, 보안, 컨트롤러, 서비스, 컴포넌트, 이벤트, 스케줄러, 도메인, 저장소, DTO와 `src/test`의 테스트를 모두 Kotlin으로 전환했다. Gradle 설정도 Kotlin DSL인 `build.gradle.kts`와 `settings.gradle.kts`를 사용한다.

## 설계 원칙

- DTO와 값 객체는 `data class`, 상태가 없는 유틸리티와 팩토리는 `object`, 정적 팩토리는 `companion object`로 표현한다.
- null 가능성은 `Optional` 대신 Kotlin nullable 타입으로 드러내고, 호출 지점에서 `requireNotNull` 또는 Elvis 연산자로 처리한다.
- Java Stream 대신 Kotlin 컬렉션 연산을 사용한다. 변경 불가능한 경로 계산 스냅샷에는 `kotlinx.collections.immutable`의 영속 컬렉션을 사용한다.
- Java 코드 생성 라이브러리와 annotation processor는 제거했다. Jackson 내부 응답 모델도 일반 Kotlin `data class`로 표현해 JVM 전용 브리지 annotation이나 Java record 접근자를 두지 않는다.
- Spring 프록시와 JPA 엔티티 생성을 위해 Kotlin Spring/JPA 컴파일러 플러그인을 사용한다. JPA 플러그인이 엔티티와 getter를 열도록 수동 `open`/`final` 지정은 피하고, 상태 프로퍼티의 setter는 도메인 외부에서 수정할 수 없도록 `protected`로 제한한다.
- 배송지 순서는 `DeliveryStop.sequence`에 저장하고 `DeliveryPlan`의 연관 컬렉션에서 해당 속성으로 정렬한다. 계획의 순서를 변경할 때 목록과 `sequence`를 함께 갱신한다.
- macOS arm64의 Netty DNS resolver를 런타임에 포함한다. JDK 25의 네이티브 접근 경고를 피하도록 실행 JAR manifest와 Gradle `JavaExec` 작업에 `ALL-UNNAMED` 네이티브 접근을 허용한다. IDE에서 메인 클래스를 직접 실행할 때는 VM 옵션에 `--enable-native-access=ALL-UNNAMED`를 추가한다.
- 웹 요청이 끝난 뒤 지연 로딩 쿼리가 발생하지 않도록 Open EntityManager in View를 비활성화한다.
- Spring Data, Servlet, Security처럼 Java로 정의된 프레임워크 인터페이스를 구현하는 곳에는 해당 API가 요구하는 메서드 시그니처만 남긴다.

## 빌드와 검증

```sh
./gradlew test
./gradlew bootJar
```

실제 카카오 API와 MySQL이 필요한 통합 테스트는 각각 기존 환경변수 조건이 충족될 때 실행된다.
