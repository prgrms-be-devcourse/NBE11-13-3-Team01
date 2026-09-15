package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import org.springframework.stereotype.Component
import java.util.PriorityQueue

/**
 * 상태 우선순위 큐로 최소 이동시간 Hamiltonian path 를 찾는 운영 기본 구현.
 *
 * ## 동점 경로에 대한 계약이 없다
 *
 * 최소 비용 경로가 여러 개일 때 어느 것을 돌려줄지 **정하지 않는다.**
 * `PriorityQueue` 는 같은 우선순위 원소의 순서를 보장하지 않고, 아래의
 * `nextTotalDuration >= knownDuration` 조건은 먼저 발견한 경로를 유지할 뿐이다.
 * 같은 입력에 대해 재현되기는 하지만 그건 후보 목록의 순회 순서가 고정돼 있어서
 * 생기는 결과이지 계약이 아니다.
 *
 * 그래서 이 구현은 differential test 에서 **총비용만** 비교 대상이고,
 * 경로 순서까지 비교하는 쪽은 사전순 최소를 계약으로 박아 둔
 * [BitmaskDpRouteOptimizer] 와 [NativeRouteOptimizer] 다.
 * 이 구현의 결과는 "최적 경로 중 하나인지"를 따로 검증한다.
 */
@Component
class DijkstraRouteOptimizer : RouteOptimizer {
    override fun optimize(context: RouteOptimizationContext): OptimizedRoute {
        val routesToVisit = PriorityQueue(compareBy<SearchNode> { it.totalDurationSeconds })
        val minimumDurationByState = mutableMapOf<RouteState, Long>()
        val initialState = RouteState(context.currentStopId, emptySet())
        val initialNode = SearchNode(initialState, emptyList(), 0L)

        routesToVisit.add(initialNode)
        minimumDurationByState[initialState] = 0L

        var expandedStateCount = 0
        while (routesToVisit.isNotEmpty()) {
            val current = routesToVisit.poll()
            if (isOutdated(current, minimumDurationByState)) {
                continue
            }

            expandedStateCount++
            if (visitedEveryCandidate(current, context)) {
                return OptimizedRoute(current.stopOrder, current.totalDurationSeconds, expandedStateCount)
            }

            visitUnvisitedStops(context, current, routesToVisit, minimumDurationByState)
        }

        throw BusinessException(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE)
    }

    private fun isOutdated(node: SearchNode, minimumDurationByState: Map<RouteState, Long>): Boolean {
        val minimumDuration = minimumDurationByState.getOrDefault(node.state, Long.MAX_VALUE)
        return node.totalDurationSeconds > minimumDuration
    }

    private fun visitedEveryCandidate(node: SearchNode, context: RouteOptimizationContext): Boolean =
        node.state.visitedStopIds.size == context.candidateStopIds.size

    private fun visitUnvisitedStops(
        context: RouteOptimizationContext,
        current: SearchNode,
        routesToVisit: PriorityQueue<SearchNode>,
        minimumDurationByState: MutableMap<RouteState, Long>,
    ) {
        for (nextStopId in context.candidateStopIds) {
            if (nextStopId in current.state.visitedStopIds) {
                continue
            }
            visitStop(context, current, nextStopId, routesToVisit, minimumDurationByState)
        }
    }

    private fun visitStop(
        context: RouteOptimizationContext,
        current: SearchNode,
        nextStopId: Long,
        routesToVisit: PriorityQueue<SearchNode>,
        minimumDurationByState: MutableMap<RouteState, Long>,
    ) {
        val travelDuration = context.travelCostMatrix.findDuration(current.state.currentStopId, nextStopId)
            ?: return

        val nextVisitedStopIds = current.state.visitedStopIds + nextStopId
        val nextStopOrder = current.stopOrder + nextStopId
        val nextTotalDuration = Math.addExact(current.totalDurationSeconds, travelDuration)
        val nextState = RouteState(nextStopId, nextVisitedStopIds)
        val knownDuration = minimumDurationByState.getOrDefault(nextState, Long.MAX_VALUE)
        if (nextTotalDuration >= knownDuration) {
            return
        }

        minimumDurationByState[nextState] = nextTotalDuration
        routesToVisit.add(SearchNode(nextState, nextStopOrder, nextTotalDuration))
    }

    private data class RouteState(val currentStopId: Long, val visitedStopIds: Set<Long>)

    private data class SearchNode(
        val state: RouteState,
        val stopOrder: List<Long>,
        val totalDurationSeconds: Long,
    )
}
