package com.example.delivery_project.service.component.route.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code libroute_optimizer} 로의 FFM(java.lang.foreign) 다운콜.
 *
 * <h2>왜 이 클래스만 Java 인가</h2>
 *
 * {@link MethodHandle#invokeExact} 는 signature-polymorphic 메서드다. 호출부에 적힌
 * 정적 타입이 그대로 호출 시그니처가 되고, JIT 은 이를 직접 호출로 낮춘다.
 * 그런데 Kotlin 은 signature-polymorphic 호출부를 만들지 못한다. Kotlin 에서
 * {@code invokeExact} 를 쓰면 일반 메서드 호출로 컴파일돼 {@code WrongMethodTypeException}
 * 이 나거나, 우회하려고 {@code invokeWithArguments} 를 쓰면 인자마다 박싱이 생긴다.
 *
 * 이 프로젝트의 목적이 **FFI 경계 비용 측정**이므로, 측정 대상에 박싱 비용이 섞이면
 * 실험 자체가 무의미해진다. 그래서 경계 한 겹만 Java 로 둔다.
 *
 * <h2>Arena 수명</h2>
 *
 * 라이브러리 조회는 {@link Arena#global()} 로 잡아 프로세스가 끝날 때까지 유지한다.
 * 반면 호출마다 쓰는 비용 행렬·결과 버퍼는 {@link Arena#ofConfined()} 로 잡아
 * try-with-resources 로 즉시 해제한다. 이 호출당 할당·복사가 곧 FFI 오버헤드의
 * 대부분이고, 벤치마크가 드러내야 하는 값이다.
 *
 * <h2>라이브러리 위치</h2>
 *
 * <ol>
 *   <li>시스템 프로퍼티 {@code route.optimizer.native.library}</li>
 *   <li>환경변수 {@code ROUTE_OPTIMIZER_NATIVE_LIBRARY}</li>
 *   <li>{@code native/route_optimizer/build/libroute_optimizer.{dylib|so}} (작업 디렉터리 기준)</li>
 * </ol>
 *
 * 어느 것도 없으면 {@link #isAvailable()} 가 false 를 돌려준다. 예외를 던지지 않는 이유는
 * 네이티브 빌드 없이도 애플리케이션이 떠야 하기 때문이다. 호출자가 Kotlin 구현으로 폴백한다.
 */
public final class RouteOptimizerNative {

    /** 네이티브 헤더의 status 코드와 같은 값이어야 한다. */
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_NO_ROUTE = 2;
    public static final int STATUS_OVERFLOW = 3;
    public static final int STATUS_OUT_OF_MEMORY = 4;

    /** 네이티브의 {@code ROUTE_MAX_NODE_COUNT} 와 같은 값이어야 한다. */
    public static final int MAX_NODE_COUNT = 17;

    /**
     * 네이티브 호출 결과.
     *
     * @param route 방문 순서. 노드 번호(1..nodeCount-1)가 들어간다.
     *              {@code status != STATUS_OK} 이면 빈 배열이다.
     */
    public record Result(int status, long totalDuration, long reachableStates, int[] route) {}

    private static final MemoryLayout ROUTE_RESULT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("status"),
            ValueLayout.JAVA_INT.withName("route_length"),
            ValueLayout.JAVA_LONG.withName("total_duration"),
            ValueLayout.JAVA_LONG.withName("reachable_states")
    );

    private static final VarHandle STATUS =
            ROUTE_RESULT.varHandle(MemoryLayout.PathElement.groupElement("status"));
    private static final VarHandle ROUTE_LENGTH =
            ROUTE_RESULT.varHandle(MemoryLayout.PathElement.groupElement("route_length"));
    private static final VarHandle TOTAL_DURATION =
            ROUTE_RESULT.varHandle(MemoryLayout.PathElement.groupElement("total_duration"));
    private static final VarHandle REACHABLE_STATES =
            ROUTE_RESULT.varHandle(MemoryLayout.PathElement.groupElement("reachable_states"));

    private static final int[] NO_ROUTE = new int[0];

    private static final MethodHandle ROUTE_OPTIMIZE;
    private static final MethodHandle ABI_VERSION;
    private static final String UNAVAILABLE_REASON;

    static {
        MethodHandle optimize = null;
        MethodHandle abiVersion = null;
        String reason = null;
        try {
            final Path library = resolveLibraryPath();
            if (library == null) {
                reason = "네이티브 라이브러리를 찾지 못했다. `make -C native/route_optimizer` 로 빌드해야 한다.";
            } else {
                // 전역 Arena: 프로세스가 끝날 때까지 라이브러리를 매핑해 둔다.
                final SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
                final Linker linker = Linker.nativeLinker();
                optimize = linker.downcallHandle(
                        lookup.find("route_optimize").orElseThrow(),
                        // 구조체를 값으로 돌려받으므로, 만들어지는 MethodHandle 의
                        // 첫 파라미터가 SegmentAllocator 로 **하나 늘어난다.**
                        FunctionDescriptor.of(
                                ROUTE_RESULT,
                                ValueLayout.JAVA_INT,   // node_count
                                ValueLayout.ADDRESS,    // const int64_t *cost_matrix
                                ValueLayout.ADDRESS     // int32_t *out_route
                        )
                );
                abiVersion = linker.downcallHandle(
                        lookup.find("route_optimizer_abi_version").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT)
                );
            }
        } catch (RuntimeException | Error problem) {
            reason = problem.getClass().getSimpleName() + ": " + problem.getMessage();
            optimize = null;
            abiVersion = null;
        }
        ROUTE_OPTIMIZE = optimize;
        ABI_VERSION = abiVersion;
        UNAVAILABLE_REASON = reason;
    }

    private RouteOptimizerNative() {
    }

    public static boolean isAvailable() {
        return ROUTE_OPTIMIZE != null;
    }

    /** 사용할 수 없는 이유. 사용 가능하면 null. */
    public static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    public static int abiVersion() {
        requireAvailable();
        try {
            return (int) ABI_VERSION.invokeExact();
        } catch (Throwable failure) {
            throw wrap(failure);
        }
    }

    /**
     * @param nodeCount 시작 노드를 포함한 노드 수
     * @param costs     {@code nodeCount * nodeCount} 개의 이동시간. 음수 = 간선 없음
     */
    public static Result optimize(int nodeCount, long[] costs) {
        requireAvailable();
        if (nodeCount < 1 || nodeCount > MAX_NODE_COUNT) {
            throw new IllegalArgumentException("nodeCount 는 1..%d 여야 한다: %d".formatted(MAX_NODE_COUNT, nodeCount));
        }
        if (costs.length != nodeCount * nodeCount) {
            throw new IllegalArgumentException(
                    "비용 행렬 크기가 맞지 않는다. 기대 %d, 실제 %d".formatted(nodeCount * nodeCount, costs.length));
        }

        final int candidateCount = nodeCount - 1;
        // 호출마다 열고 닫는다. 이 할당과 복사가 곧 FFI 오버헤드다.
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment costSegment = arena.allocateFrom(ValueLayout.JAVA_LONG, costs);
            final MemorySegment routeSegment = candidateCount == 0
                    ? MemorySegment.NULL
                    : arena.allocate(ValueLayout.JAVA_INT, candidateCount);

            final MemorySegment result = (MemorySegment) ROUTE_OPTIMIZE.invokeExact(
                    (SegmentAllocator) arena, nodeCount, costSegment, routeSegment);

            final int status = (int) STATUS.get(result, 0L);
            if (status != STATUS_OK) {
                return new Result(status, 0L, 0L, NO_ROUTE);
            }
            final int routeLength = (int) ROUTE_LENGTH.get(result, 0L);
            final int[] route = routeLength == 0
                    ? NO_ROUTE
                    : routeSegment.toArray(ValueLayout.JAVA_INT);
            return new Result(
                    status,
                    (long) TOTAL_DURATION.get(result, 0L),
                    (long) REACHABLE_STATES.get(result, 0L),
                    route
            );
        } catch (Throwable failure) {
            throw wrap(failure);
        }
    }

    private static void requireAvailable() {
        if (ROUTE_OPTIMIZE == null) {
            throw new IllegalStateException("네이티브 경로 최적화를 쓸 수 없다. " + UNAVAILABLE_REASON);
        }
    }

    private static RuntimeException wrap(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("네이티브 호출이 실패했다.", failure);
    }

    private static Path resolveLibraryPath() {
        final String explicit = System.getProperty("route.optimizer.native.library",
                System.getenv("ROUTE_OPTIMIZER_NATIVE_LIBRARY"));
        if (explicit != null && !explicit.isBlank()) {
            final Path path = Path.of(explicit);
            return Files.isRegularFile(path) ? path : null;
        }
        final Path fallback = Path.of("native", "route_optimizer", "build", defaultLibraryName());
        return Files.isRegularFile(fallback) ? fallback.toAbsolutePath() : null;
    }

    private static String defaultLibraryName() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "libroute_optimizer.dylib";
        }
        if (os.contains("win")) {
            return "route_optimizer.dll";
        }
        return "libroute_optimizer.so";
    }
}
