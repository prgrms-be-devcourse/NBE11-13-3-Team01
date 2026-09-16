plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    kotlin("plugin.jpa") version "2.4.20"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "delivery_project"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xemit-jvm-type-annotations")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos") {
        artifact {
            classifier = "osx-aarch_64"
        }
    }

    testImplementation(kotlin("test"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
    // FFM 다운콜은 JDK 24+ 에서 명시적 허용이 없으면 경고를 낸다.
    // bootJar/JavaExec 에는 이미 걸려 있지만 Test 태스크는 JavaExec 이 아니라 따로 필요하다.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ---------------------------------------------------------------------------
// 네이티브 경로 최적화 (연습용 폴리글랏 실험)
//
// 기본 build 에 걸지 않는다. make/컴파일러가 없는 환경에서도 ./gradlew build 가
// 그대로 돌아가야 하고, 네이티브 라이브러리가 없으면 Kotlin 구현으로 폴백하기 때문이다.
//   ./gradlew buildNativeRouteOptimizer   공유 라이브러리 빌드
//   ./gradlew testNativeRouteOptimizer    완전탐색 대조 + 경계 조건
//   ./gradlew sanitizeNativeRouteOptimizer  ASan/UBSan 으로 같은 테스트
// ---------------------------------------------------------------------------
val nativeRouteOptimizerDir = layout.projectDirectory.dir("native/route_optimizer")

fun registerNativeMake(taskName: String, makeTarget: String, taskDescription: String) =
    tasks.register<Exec>(taskName) {
        group = "native"
        description = taskDescription
        workingDir = nativeRouteOptimizerDir.asFile
        commandLine("make", makeTarget)
        inputs.files(
            nativeRouteOptimizerDir.file("route_optimizer.c"),
            nativeRouteOptimizerDir.file("route_optimizer.h"),
            nativeRouteOptimizerDir.file("test_route_optimizer.c"),
            nativeRouteOptimizerDir.file("Makefile"),
        )
        outputs.dir(nativeRouteOptimizerDir.dir("build"))
        onlyIf {
            val present = nativeRouteOptimizerDir.asFile.resolve("Makefile").isFile
            if (!present) logger.lifecycle("native/route_optimizer 가 없어 건너뛴다.")
            present
        }
    }

registerNativeMake("buildNativeRouteOptimizer", "all", "네이티브 경로 최적화 공유 라이브러리를 빌드한다.")
registerNativeMake("testNativeRouteOptimizer", "test", "네이티브 구현을 완전탐색과 대조한다.")
registerNativeMake("sanitizeNativeRouteOptimizer", "sanitize", "ASan/UBSan 으로 네이티브 테스트를 돌린다.")

// crossover point 측정기. JMH 가 아니라 직접 돌리는 하네스다(클래스 주석 참고).
tasks.register<JavaExec>("benchmarkRouteOptimizer") {
    group = "native"
    description = "Dijkstra / Kotlin DP / 네이티브 구현의 후보 수별 실행시간을 잰다."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.example.delivery_project.service.component.route.RouteOptimizerBenchmark"
}

tasks.bootJar {
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
