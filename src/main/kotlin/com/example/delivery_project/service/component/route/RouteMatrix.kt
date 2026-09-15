package com.example.delivery_project.service.component.route

/**
 * [RouteOptimizationContext] 를 **평탄한 비용 행렬**로 바꾼다.
 *
 * bitmask DP 구현(Kotlin, C)이 공유하는 입력 형태다. 두 구현이 같은 표현을 받아야
 * differential test 가 알고리즘 차이만 비교하게 된다.
 *
 * 노드 번호를 새로 매기는 이유가 있다. 실제 stop id 에는 `Long.MIN_VALUE`(출발지 노드)
 * 같은 값이 섞여 있어 배열 첨자로 쓸 수 없고, C 쪽에 그대로 넘길 수도 없다.
 * 그래서 `0 = 현재 위치`, `1..n = 후보(주어진 순서대로)` 로 다시 매긴다.
 *
 * 비용 행렬은 row-major 이고 `costs[from * nodeCount + to]` 가 이동시간(초)이다.
 * **음수는 간선이 없다는 뜻**이다. [TravelCostMatrix] 가 음수 이동시간을 이미 금지하므로
 * -1 은 안전하게 비어 있는 값이다. `Long.MAX_VALUE` 를 sentinel 로 쓰면 더하는 순간
 * 오버플로가 나므로 쓰지 않는다.
 */
internal class RouteMatrix private constructor(
    val nodeCount: Int,
    val costs: LongArray,
    private val stopIdByNode: LongArray,
) {
    /** 시작 노드를 뺀 후보 수. */
    val candidateCount: Int get() = nodeCount - 1

    fun cost(from: Int, to: Int): Long = costs[from * nodeCount + to]

    /** 노드 번호 수열을 원래 stop id 수열로 되돌린다. */
    fun toStopIds(route: IntArray, length: Int): List<Long> {
        val stopIds = ArrayList<Long>(length)
        for (index in 0 until length) {
            stopIds.add(stopIdByNode[route[index]])
        }
        return stopIds
    }

    companion object {
        /** 간선이 없음을 나타내는 값. */
        const val NO_EDGE: Long = -1L

        /**
         * 시작 노드를 포함한 최대 노드 수.
         * 네이티브 구현의 `ROUTE_MAX_NODE_COUNT` 와 **같은 값이어야 한다.**
         */
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
