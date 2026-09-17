package com.example.delivery_project.service.component.route

internal class RouteMatrix private constructor(
    val nodeCount: Int,
    val costs: LongArray,
    private val stopIdByNode: LongArray,
) {
    val candidateCount: Int get() = nodeCount - 1

    fun cost(from: Int, to: Int): Long = costs[from * nodeCount + to]

    fun toStopIds(route: IntArray, length: Int): List<Long> {
        val stopIds = ArrayList<Long>(length)
        for (index in 0 until length) {
            stopIds.add(stopIdByNode[route[index]])
        }
        return stopIds
    }

    companion object {
        const val NO_EDGE: Long = -1L

        const val MAX_NODE_COUNT: Int = 17

        fun from(context: RouteOptimizationContext): RouteMatrix {
            val nodeCount = context.candidateStopIds.size + 1
            require(nodeCount <= MAX_NODE_COUNT) {
                "후보 배송지는 최대 ${MAX_NODE_COUNT - 1}개까지 처리한다. 요청: ${context.candidateStopIds.size}개"
            }

            val stopIdByNode = LongArray(nodeCount)
            stopIdByNode[0] = context.currentStopId
            context.candidateStopIds.forEachIndexed { index, stopId ->
                stopIdByNode[index + 1] = stopId
            }

            val costs = LongArray(nodeCount * nodeCount) { NO_EDGE }
            for (from in 0 until nodeCount) {
                for (to in 0 until nodeCount) {
                    if (from == to) {
                        continue
                    }
                    val duration = context.travelCostMatrix
                        .findDuration(stopIdByNode[from], stopIdByNode[to])
                        ?: continue
                    require(duration >= 0) { "이동시간은 음수일 수 없습니다." }
                    costs[from * nodeCount + to] = duration
                }
            }

            return RouteMatrix(nodeCount, costs, stopIdByNode)
        }
    }
}
