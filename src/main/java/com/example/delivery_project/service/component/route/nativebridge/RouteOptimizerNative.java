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

public final class RouteOptimizerNative {

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_NO_ROUTE = 2;
    public static final int STATUS_OVERFLOW = 3;
    public static final int STATUS_OUT_OF_MEMORY = 4;

    public static final int MAX_NODE_COUNT = 17;

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
                final SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
                final Linker linker = Linker.nativeLinker();
                optimize = linker.downcallHandle(
                        lookup.find("route_optimize").orElseThrow(),
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
