package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.global.BusinessException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 세 구현이 같은 답을 내는지 무작위 입력으로 대조한다.
 *
 * 비교 기준이 구현마다 다르다는 점이 중요하다.
 *
 * - [BitmaskDpRouteOptimizer] 와 [NativeRouteOptimizer] 는 동점 시 **사전순 최소**를
 *   계약으로 박아 두었으므로 총비용과 **경로 순서까지** 같아야 한다.
 * - [DijkstraRouteOptimizer] 는 동점 처리 계약이 없다. 그래서 총비용만 비교하고,
 *   돌려준 경로가 실제로 유효한 최적 경로인지는 따로 검증한다.
 *
 * 비용 범위를 좁게(0..2) 잡는 경우를 일부러 섞는다. 동점이 많이 생겨야 tie-break 가
 * 실제로 검증되기 때문이다. 넓은 범위만 쓰면 동점이 거의 안 나와 통과해도 의미가 없다.
 */
class RouteOptimizerDifferentialTest {
    private val dijkstra: RouteOptimizer = DijkstraRouteOptimizer()
    private val bitmaskDp: RouteOptimizer = BitmaskDpRouteOptimizer()
    private val nativeOptimizer = NativeRouteOptimizer()

    @Test
    fun bitmask_DP_는_완전탐색과_같은_비용과_사전순_최소_경로를_낸다() {
        forEachRandomCase { nodeCount, costs ->
            val expected = bruteForce(nodeCount, costs)
            val context = contextOf(nodeCount, costs)

            if (expected == null) {
                assertThrowsNoRoute(bitmaskDp, context, nodeCount, costs)
                return@forEachRandomCase
            }

            val actual = bitmaskDp.optimize(context)
            checkEquals(expected.totalDuration, actual.totalDurationSeconds, nodeCount, costs)
            checkEquals(expected.route, nodeIndexesOf(nodeCount, actual.stopIds), nodeCount, costs)
        }
    }

    @Test
    fun 세_구현의_총_이동시간이_모두_같다() {
        forEachRandomCase { nodeCount, costs ->
            val context = contextOf(nodeCount, costs)
            val expected = bruteForce(nodeCount, costs)
            if (expected == null) {
                assertThrowsNoRoute(dijkstra, context, nodeCount, costs)
                assertThrowsNoRoute(bitmaskDp, context, nodeCount, costs)
                if (nativeOptimizer.usesNative) {
                    assertThrowsNoRoute(nativeOptimizer, context, nodeCount, costs)
                }
                return@forEachRandomCase
            }

            checkEquals(expected.totalDuration, dijkstra.optimize(context).totalDurationSeconds, nodeCount, costs)
            checkEquals(expected.totalDuration, bitmaskDp.optimize(context).totalDurationSeconds, nodeCount, costs)
            if (nativeOptimizer.usesNative) {
                checkEquals(expected.totalDuration, nativeOptimizer.optimize(context).totalDurationSeconds, nodeCount, costs)
            }
        }
    }

    @Test
    fun 네이티브와_Kotlin_DP_는_경로_순서까지_같다() {
        assumeTrue(nativeOptimizer.usesNative, "네이티브 라이브러리가 없다. ./gradlew buildNativeRouteOptimizer 로 빌드한다.")

        forEachRandomCase { nodeCount, costs ->
            val context = contextOf(nodeCount, costs)
            if (bruteForce(nodeCount, costs) == null) {
                return@forEachRandomCase
            }
            val fromKotlin = bitmaskDp.optimize(context)
            val fromNative = nativeOptimizer.optimize(context)

            checkEquals(fromKotlin.totalDurationSeconds, fromNative.totalDurationSeconds, nodeCount, costs)
            checkEquals(fromKotlin.stopIds, fromNative.stopIds, nodeCount, costs)
        }
    }

    @Test
    fun Dijkstra_가_돌려준_경로는_실제로_최적_경로_중_하나다() {
        forEachRandomCase { nodeCount, costs ->
            val expected = bruteForce(nodeCount, costs) ?: return@forEachRandomCase
            val result = dijkstra.optimize(contextOf(nodeCount, costs))
            val route = nodeIndexesOf(nodeCount, result.stopIds)

            checkEquals(nodeCount - 1, route.size, nodeCount, costs)
            checkEquals(route.size, route.toSet().size, nodeCount, costs)

            // 리포트된 총비용이 아니라 **행렬을 다시 걸어서** 실제 비용을 확인한다.
            var walked = 0L
            var current = 0
            for (node in route) {
                val edge = costs[current * nodeCount + node]
                if (edge < 0) {
                    fail("존재하지 않는 간선을 지나갔다. ${describe(nodeCount, costs)}")
                }
                walked += edge
                current = node
            }
            checkEquals(result.totalDurationSeconds, walked, nodeCount, costs)
            checkEquals(expected.totalDuration, walked, nodeCount, costs)
        }
    }

    @Test
    fun 후보가_없으면_세_구현_모두_빈_경로를_돌려준다() {
        val context = contextOf(1, longArrayOf(RouteMatrix.NO_EDGE))

        for (optimizer in listOfNotNull(dijkstra, bitmaskDp, nativeOptimizer)) {
            val result = optimizer.optimize(context)
            assertTrue(result.stopIds.isEmpty(), optimizer::class.simpleName)
            assertEquals(0L, result.totalDurationSeconds, optimizer::class.simpleName)
        }
    }

    @Test
    fun 이동시간_합이_long_범위를_넘으면_ArithmeticException_이다() {
        val nodeCount = 3
        val costs = LongArray(nodeCount * nodeCount) { RouteMatrix.NO_EDGE }
        costs[0 * nodeCount + 1] = Long.MAX_VALUE - 1
        costs[1 * nodeCount + 2] = 10L
        val context = contextOf(nodeCount, costs)

        runCatching { bitmaskDp.optimize(context) }
            .onSuccess { fail("오버플로인데 성공했다: $it") }
            .onFailure { assertTrue(it is ArithmeticException, "기대 ArithmeticException, 실제 ${it::class.simpleName}") }

        if (nativeOptimizer.usesNative) {
            runCatching { nativeOptimizer.optimize(context) }
                .onSuccess { fail("네이티브가 오버플로인데 성공했다: $it") }
                .onFailure { assertTrue(it is ArithmeticException, "기대 ArithmeticException, 실제 ${it::class.simpleName}") }
        }
    }

    // ------------------------------------------------------------------ 도구

    /**
     * 단언이 100만 번 가까이 돌아가므로 실패 메시지를 **실패했을 때만** 만든다.
     * [describe] 를 assertEquals 인자로 넘기면 매번 행렬 전체를 문자열로 만들어
     * 테스트 시간이 수십 배로 늘어난다.
     */
    private fun checkEquals(expected: Any?, actual: Any?, nodeCount: Int, costs: LongArray) {
        if (expected != actual) {
            fail("기대 <$expected>, 실제 <$actual> — ${describe(nodeCount, costs)}")
        }
    }

    private fun assertThrowsNoRoute(
        optimizer: RouteOptimizer,
        context: RouteOptimizationContext,
        nodeCount: Int,
        costs: LongArray,
    ) {
        runCatching { optimizer.optimize(context) }
            .onSuccess { fail("완주 경로가 없는데 성공했다. ${describe(nodeCount, costs)}") }
            .onFailure { assertTrue(it is BusinessException, "기대 BusinessException, 실제 ${it::class.simpleName}") }
    }

    /**
     * 후보 수와 비용 분포를 바꿔가며 무작위 행렬을 만든다.
     * 완전탐색과 대조하므로 후보 수는 7개까지만 올린다.
     */
    private fun forEachRandomCase(action: (nodeCount: Int, costs: LongArray) -> Unit) {
        val random = Random(20260915)
        for (nodeCount in 1..8) {
            for (high in listOf(1L, 2L, 5L, 1000L)) {
                for (missingPercent in listOf(0, 0, 20, 50)) {
                    repeat(if (nodeCount <= 6) 40 else 12) {
                        action(nodeCount, randomCosts(random, nodeCount, high, missingPercent))
                    }
                }
            }
        }
    }

    private fun randomCosts(random: Random, nodeCount: Int, high: Long, missingPercent: Int): LongArray {
        val costs = LongArray(nodeCount * nodeCount) { RouteMatrix.NO_EDGE }
        for (from in 0 until nodeCount) {
            for (to in 0 until nodeCount) {
                if (from == to) {
                    continue
                }
                if (random.nextInt(100) < missingPercent) {
                    continue
                }
                costs[from * nodeCount + to] = random.nextLong(0, high + 1)
            }
        }
        return costs
    }

    private fun stopIdsFor(nodeCount: Int): List<Long> =
        // 시작 노드에 Long.MIN_VALUE 를 넣는다. 운영 코드의 DEPARTURE_NODE_ID 와 같은 값이고,
        // stop id 를 배열 첨자로 쓰면 안 된다는 것을 테스트에서도 강제한다.
        listOf(Long.MIN_VALUE) + (1 until nodeCount).map { it * 37L - 500L }

    private fun contextOf(nodeCount: Int, costs: LongArray): RouteOptimizationContext {
        val stopIds = stopIdsFor(nodeCount)
        val legs = HashMap<RouteLeg, Long>()
        for (from in 0 until nodeCount) {
            for (to in 0 until nodeCount) {
                if (from == to) {
                    continue
                }
                val cost = costs[from * nodeCount + to]
                if (cost >= 0) {
                    legs[RouteLeg(stopIds[from], stopIds[to])] = cost
                }
            }
        }
        return RouteOptimizationContext(stopIds[0], stopIds.drop(1), TravelCostMatrix(legs))
    }

    private fun nodeIndexesOf(nodeCount: Int, stopIds: List<Long>): List<Int> {
        val all = stopIdsFor(nodeCount)
        return stopIds.map { all.indexOf(it) }
    }

    private data class BruteForceResult(val totalDuration: Long, val route: List<Int>)

    /**
     * 모든 순열을 **사전순으로** 훑어 최소 비용과 그 비용을 내는 첫 순열을 구한다.
     * 더 나을 때만 갱신하므로 결과가 곧 사전순 최소 경로다.
     */
    private fun bruteForce(nodeCount: Int, costs: LongArray): BruteForceResult? {
        val candidateCount = nodeCount - 1
        if (candidateCount == 0) {
            return BruteForceResult(0L, emptyList())
        }

        var found = false
        var bestCost = Long.MAX_VALUE
        var bestRoute: List<Int> = emptyList()

        permutations(candidateCount) { permutation ->
            var total = 0L
            var current = 0
            var reachable = true
            for (node in permutation) {
                val edge = costs[current * nodeCount + node]
                if (edge < 0 || edge > Long.MAX_VALUE - total) {
                    reachable = false
                    break
                }
                total += edge
                current = node
            }
            if (reachable && (!found || total < bestCost)) {
                found = true
                bestCost = total
                bestRoute = permutation.toList()
            }
        }

        return if (found) BruteForceResult(bestCost, bestRoute) else null
    }

    /** 1..candidateCount 의 순열을 사전순으로 방문한다. */
    private fun permutations(candidateCount: Int, action: (List<Int>) -> Unit) {
        val used = BooleanArray(candidateCount + 1)
        val current = ArrayList<Int>(candidateCount)

        fun step() {
            if (current.size == candidateCount) {
                action(current)
                return
            }
            for (node in 1..candidateCount) {
                if (used[node]) {
                    continue
                }
                used[node] = true
                current.add(node)
                step()
                current.removeAt(current.size - 1)
                used[node] = false
            }
        }
        step()
    }

    private fun describe(nodeCount: Int, costs: LongArray): String =
        "nodeCount=$nodeCount costs=${costs.toList()}"
}
