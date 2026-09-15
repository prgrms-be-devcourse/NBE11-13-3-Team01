package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException

/**
 * Held-Karp 형태의 bitmask DP. [DijkstraRouteOptimizer] 와 **같은 문제**를 푼다.
 *
 * 이 구현이 있는 이유는 성능 개선이 아니라 **비교 기준**이다.
 * 네이티브(C) 구현과 기존 Kotlin 구현을 바로 비교하면, 차이의 대부분이
 * 언어가 아니라 자료구조(우선순위 큐 + `Set<Long>` 할당 vs 평탄 배열)에서 온다.
 * 같은 알고리즘을 JVM 에서 한 번 구현해 두어야 `이 구현 ↔ 네이티브` 비교가
 * 비로소 **언어와 FFI 경계 비용만** 재게 된다.
 *
 * ## 동점 처리
 *
 * 최소 비용 경로가 여러 개면 **노드 번호 수열이 사전순으로 가장 작은 것**을 돌려준다.
 * 이 계약이 네이티브 구현과 글자 그대로 같아야 differential test 가 총비용뿐 아니라
 * 경로 순서까지 비교할 수 있다.
 *
 * 그래서 DP 를 앞에서부터 채우고 parent 포인터로 되짚지 않는다. 그러면 "최소 비용 경로 중
 * 하나"만 나오고 그게 어느 것인지는 갱신 순서에 달린다. 대신 뒤에서부터
 * `remaining[mask][last]` = 남은 후보를 전부 방문하는 최소 추가 비용을 채운 뒤,
 * 앞에서부터 걸어가며 매 단계 최적을 유지하는 후보 중 번호가 가장 작은 것을 고른다.
 *
 * ## expandedStateCount
 *
 * [DijkstraRouteOptimizer] 와 **정의가 다르다.** 여기서는 도달 가능하다고 판정된
 * (방문집합, 현재노드) 상태 수다. 성능 관찰용이며 구현 간 동등성 비교 대상이 아니다.
 */
class BitmaskDpRouteOptimizer : RouteOptimizer {
    override fun optimize(context: RouteOptimizationContext): OptimizedRoute {
        val matrix = RouteMatrix.from(context)
        val candidateCount = matrix.candidateCount
        if (candidateCount == 0) {
            return OptimizedRoute(emptyList(), 0L, 1)
        }

        val nodeCount = matrix.nodeCount
        val fullMask = (1 shl candidateCount) - 1
        val remaining = LongArray((fullMask + 1) * nodeCount) { INFINITE }
        for (last in 0 until nodeCount) {
            remaining[fullMask * nodeCount + last] = 0L
        }

        var reachableStates = 0
        var overflowed = false

        // mask 를 내림차순으로 훑으면 mask or bit 가 항상 먼저 채워져 있다.
        for (mask in fullMask - 1 downTo 0) {
            for (last in 0 until nodeCount) {
                if (last > 0 && (mask shr (last - 1)) and 1 == 0) {
                    continue // 아직 방문하지 않은 후보에 서 있을 수는 없다
                }
                if (last == 0 && mask != 0) {
                    continue // 시작 노드에 서 있는 건 아무것도 방문하지 않은 시점뿐이다
                }

                var best = INFINITE
                for (candidate in 0 until candidateCount) {
                    if ((mask shr candidate) and 1 == 1) {
                        continue
                    }
                    val nextNode = candidate + 1
                    val edge = matrix.cost(last, nextNode)
                    if (edge < 0) {
                        continue // 간선 없음
                    }
                    val tail = remaining[(mask or (1 shl candidate)) * nodeCount + nextNode]
                    if (tail == INFINITE) {
                        continue
                    }
                    // 둘 다 음수가 아니므로 이 비교가 곧 정확한 오버플로 검사다.
                    if (edge > Long.MAX_VALUE - tail) {
                        overflowed = true
                        continue
                    }
                    val total = edge + tail
                    if (total < best) {
                        best = total
                    }
                }

                remaining[mask * nodeCount + last] = best
                if (best != INFINITE) {
                    reachableStates++
                }
            }
        }

        val optimum = remaining[0]
        if (optimum == INFINITE) {
            // 오버플로 때문에 모든 경로가 잘려 나갔다면 "경로 없음"이 아니다.
            // 기존 Dijkstra 구현이 Math.addExact 로 던지는 것과 같은 예외로 맞춘다.
            if (overflowed) {
                throw ArithmeticException("이동시간 합이 long 범위를 넘었습니다.")
            }
            throw BusinessException(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE)
        }

        val route = reconstruct(matrix, remaining, optimum, candidateCount)
        return OptimizedRoute(matrix.toStopIds(route, candidateCount), optimum, reachableStates)
    }

    /** 앞에서부터 걸으며 최적을 유지하는 후보 중 번호가 가장 작은 것을 고른다. */
    private fun reconstruct(
        matrix: RouteMatrix,
        remaining: LongArray,
        optimum: Long,
        candidateCount: Int,
    ): IntArray {
        val nodeCount = matrix.nodeCount
        val route = IntArray(candidateCount)
        var mask = 0
        var current = 0
        var left = optimum

        for (step in 0 until candidateCount) {
            var chosen = -1
            for (candidate in 0 until candidateCount) {
                if ((mask shr candidate) and 1 == 1) {
                    continue
                }
                val nextNode = candidate + 1
                val edge = matrix.cost(current, nextNode)
                if (edge < 0) {
                    continue
                }
                val tail = remaining[(mask or (1 shl candidate)) * nodeCount + nextNode]
                if (tail == INFINITE || edge > Long.MAX_VALUE - tail) {
                    continue
                }
                if (edge + tail == left) {
                    chosen = candidate // 오름차순으로 훑으므로 처음 맞는 것이 사전순 최소다
                    break
                }
            }
            check(chosen >= 0) { "DP 표와 경로 재구성이 어긋났습니다. (step=$step)" }

            val nextNode = chosen + 1
            left -= matrix.cost(current, nextNode)
            mask = mask or (1 shl chosen)
            current = nextNode
            route[step] = nextNode
        }
        return route
    }

    private companion object {
        const val INFINITE: Long = Long.MAX_VALUE
    }
}
