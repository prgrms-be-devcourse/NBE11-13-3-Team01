package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException

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
            if (overflowed) {
                throw ArithmeticException("이동시간 합이 long 범위를 넘었습니다.")
            }
            throw BusinessException(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE)
        }

        val route = reconstruct(matrix, remaining, optimum, candidateCount)
        return OptimizedRoute(matrix.toStopIds(route, candidateCount), optimum, reachableStates)
    }

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
