package com.example.delivery_project.service.component.route;

import com.example.delivery_project.service.component.route.nativebridge.RouteOptimizerNative;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 세 구현의 crossover point 를 찾는 측정기.
 *
 * <h2>이것은 JMH 가 아니다</h2>
 *
 * Gradle 9 에서 검증된 JMH 플러그인 조합을 확인하지 못해 직접 돌리는 하네스로 두었다.
 * JMH 가 해 주는 것 중 여기서 흉내만 낸 것은 워밍업과 DCE 방지({@link #SINK})이고,
 * 해 주지 못하는 것은 fork 분리, 프로파일러 연동, 통계적 신뢰구간이다.
 * 그래서 이 숫자는 <b>어느 구간에서 순서가 뒤집히는지</b>를 보는 용도이지,
 * "몇 % 빠르다"를 주장할 근거로는 약하다. 결론을 그렇게 쓰면 안 된다.
 *
 * <h2>무엇을 나눠 재는가</h2>
 *
 * <ul>
 *   <li><b>Dijkstra</b> — 운영 기본 구현. 우선순위 큐 + Set 할당</li>
 *   <li><b>Kotlin DP</b> — 같은 알고리즘을 JVM 에서. 여기까지가 "언어를 바꾸지 않고 얻는 것"</li>
 *   <li><b>Native(전체)</b> — 행렬 구성 + FFI 마샬링 + C DP</li>
 *   <li><b>Native(FFI만)</b> — 이미 만들어 둔 행렬로 FFI 호출만. Arena 할당·복사·다운콜 비용</li>
 * </ul>
 *
 * <b>Kotlin DP ↔ Native(전체)</b> 가 언어 경계의 실제 손익이고,
 * <b>Native(전체) − Native(FFI만)</b> 이 행렬 구성 비용이다.
 *
 * <pre>
 *   ./gradlew buildNativeRouteOptimizer benchmarkRouteOptimizer
 * </pre>
 */
public final class RouteOptimizerBenchmark {

    /** 결과를 소비해 JIT 이 계산을 통째로 지우지 못하게 한다. */
    public static long SINK;

    private static final int[] CANDIDATE_COUNTS = {5, 8, 10, 12, 14, 16};
    private static final long WARMUP_NANOS = 1_500_000_000L;
    private static final int ROUNDS = 7;

    private RouteOptimizerBenchmark() {
    }

    public static void main(String[] args) {
        final DijkstraRouteOptimizer dijkstra = new DijkstraRouteOptimizer();
        final BitmaskDpRouteOptimizer kotlinDp = new BitmaskDpRouteOptimizer();
        final NativeRouteOptimizer nativeOptimizer = new NativeRouteOptimizer();

        System.out.println("네이티브 사용 가능: " + RouteOptimizerNative.isAvailable());
        if (!RouteOptimizerNative.isAvailable()) {
            System.out.println("  이유: " + RouteOptimizerNative.unavailableReason());
            System.out.println("  ./gradlew buildNativeRouteOptimizer 로 빌드하면 네이티브 열이 채워진다.");
        }
        System.out.printf(Locale.ROOT, "워밍업 %.1f초 / 측정 %d회 중앙값 / 단위 마이크로초%n%n",
                WARMUP_NANOS / 1e9, ROUNDS);
        System.out.println("| 후보 수 | Dijkstra | Kotlin DP | Native(전체) | Native(FFI만) | DP 대비 Native |");
        System.out.println("| ---: | ---: | ---: | ---: | ---: | ---: |");

        for (final int candidateCount : CANDIDATE_COUNTS) {
            final Case input = Case.random(candidateCount, 20260915L + candidateCount);

            final double dijkstraTime = candidateCount <= 12
                    ? measure(() -> SINK += dijkstra.optimize(input.context).getTotalDurationSeconds())
                    : Double.NaN; // 상태 폭발로 너무 느려 의미가 없다
            final double kotlinTime = measure(() -> SINK += kotlinDp.optimize(input.context).getTotalDurationSeconds());

            double nativeTime = Double.NaN;
            double ffiTime = Double.NaN;
            if (RouteOptimizerNative.isAvailable()) {
                nativeTime = measure(() -> SINK += nativeOptimizer.optimize(input.context).getTotalDurationSeconds());
                ffiTime = measure(() -> SINK += RouteOptimizerNative.optimize(input.nodeCount, input.costs).totalDuration());
            }

            final String ratio = Double.isNaN(nativeTime) || kotlinTime == 0
                    ? "-"
                    : String.format(Locale.ROOT, "%.2f배", nativeTime / kotlinTime);
            System.out.printf(Locale.ROOT, "| %d | %s | %s | %s | %s | %s |%n",
                    candidateCount, micros(dijkstraTime), micros(kotlinTime),
                    micros(nativeTime), micros(ffiTime), ratio);
        }

        System.out.println();
        System.out.println("'DP 대비 Native' 가 1.00 을 아래로 내려가는 지점이 crossover 다.");
        System.out.println("그 위에서는 같은 알고리즘이라도 JVM 쪽이 빠르다는 뜻이고, FFI 왕복이 원인이다.");
        System.out.println("sink=" + SINK);
    }

    private static String micros(double nanos) {
        return Double.isNaN(nanos) ? "-" : String.format(Locale.ROOT, "%.2f", nanos / 1000.0);
    }

    /** 워밍업 후 여러 라운드를 재고 중앙값을 돌려준다. 단위는 나노초/회. */
    private static double measure(Runnable operation) {
        long warmupEnd = System.nanoTime() + WARMUP_NANOS;
        int warmupOps = 0;
        while (System.nanoTime() < warmupEnd) {
            operation.run();
            warmupOps++;
        }
        // 라운드당 대략 100ms 가 되도록 반복 횟수를 잡는다.
        final int opsPerRound = Math.max(1, warmupOps / 15);

        final List<Double> rounds = new ArrayList<>(ROUNDS);
        for (int round = 0; round < ROUNDS; round++) {
            final long start = System.nanoTime();
            for (int i = 0; i < opsPerRound; i++) {
                operation.run();
            }
            rounds.add((double) (System.nanoTime() - start) / opsPerRound);
        }
        rounds.sort(Double::compareTo);
        return rounds.get(rounds.size() / 2);
    }

    /** 하나의 측정 입력. 세 구현이 똑같은 행렬을 보도록 한 번만 만든다. */
    private static final class Case {
        final int nodeCount;
        final long[] costs;
        final RouteOptimizationContext context;

        private Case(int nodeCount, long[] costs, RouteOptimizationContext context) {
            this.nodeCount = nodeCount;
            this.costs = costs;
            this.context = context;
        }

        static Case random(int candidateCount, long seed) {
            final int nodeCount = candidateCount + 1;
            final Random random = new Random(seed);

            // 간선을 끊지 않는다. 끊으면 구현마다 가지치기 양이 달라져 비교가 흐려진다.
            final long[] costs = new long[nodeCount * nodeCount];
            for (int from = 0; from < nodeCount; from++) {
                for (int to = 0; to < nodeCount; to++) {
                    costs[from * nodeCount + to] = from == to ? -1L : 60L + random.nextInt(3000);
                }
            }

            final List<Long> stopIds = new ArrayList<>(nodeCount);
            stopIds.add(Long.MIN_VALUE);
            for (int index = 1; index < nodeCount; index++) {
                stopIds.add(index * 37L - 500L);
            }

            final Map<RouteLeg, Long> legs = new HashMap<>();
            for (int from = 0; from < nodeCount; from++) {
                for (int to = 0; to < nodeCount; to++) {
                    if (from == to) {
                        continue;
                    }
                    legs.put(new RouteLeg(stopIds.get(from), stopIds.get(to)), costs[from * nodeCount + to]);
                }
            }

            final RouteOptimizationContext context = new RouteOptimizationContext(
                    stopIds.get(0), stopIds.subList(1, nodeCount), new TravelCostMatrix(legs));
            return new Case(nodeCount, costs, context);
        }
    }
}
