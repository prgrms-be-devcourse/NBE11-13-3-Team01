package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import org.springframework.stereotype.Component
import java.util.PriorityQueue

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
