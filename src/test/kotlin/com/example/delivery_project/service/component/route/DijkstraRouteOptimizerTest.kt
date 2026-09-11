package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import org.junit.jupiter.api.Test
import kotlin.test.*

class DijkstraRouteOptimizerTest {
    private val optimizer: RouteOptimizer = DijkstraRouteOptimizer()

    @Test
    fun 전체_이동시간이_가장_짧은_배송지_순서를_반환한다() {
        val result = optimizer.optimize(context(0, listOf(1, 2, 3), mapOf(
            leg(0, 1) to 10L, leg(0, 2) to 3L, leg(0, 3) to 8L,
            leg(1, 2) to 10L, leg(1, 3) to 2L, leg(2, 1) to 4L,
            leg(2, 3) to 10L, leg(3, 1) to 1L, leg(3, 2) to 2L,
        )))
        assertEquals(listOf(2L, 1L, 3L), result.stopIds)
        assertEquals(9L, result.totalDurationSeconds)
    }

    @Test
    fun 가장_가까운_배송지부터_방문하는_탐욕_경로보다_짧은_경로를_찾는다() {
        val result = optimizer.optimize(context(0, listOf(1, 2, 3), mapOf(
            leg(0, 1) to 1L, leg(0, 2) to 2L, leg(0, 3) to 50L,
            leg(1, 2) to 100L, leg(1, 3) to 100L, leg(2, 1) to 1L,
            leg(2, 3) to 1L, leg(3, 1) to 1L, leg(3, 2) to 1L,
        )))
        assertEquals(listOf(2L, 3L, 1L), result.stopIds)
        assertEquals(4L, result.totalDurationSeconds)
    }

    @Test
    fun 후보가_없으면_빈_경로를_반환한다() {
        val result = optimizer.optimize(context(0, emptyList(), emptyMap()))
        assertTrue(result.stopIds.isEmpty())
        assertEquals(0L, result.totalDurationSeconds)
    }

    @Test
    fun 모든_후보를_방문할_경로가_없으면_실패한다() {
        val failure = assertFailsWith<BusinessException> {
            optimizer.optimize(context(0, listOf(1, 2), mapOf(leg(0, 1) to 1L)))
        }
        assertEquals(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE, failure.errorCode)
    }

    @Test
    fun 후보_배송지_ID는_중복될_수_없다() {
        assertFailsWith<IllegalArgumentException> { context(0, listOf(1, 1), emptyMap()) }
    }

    @Test
    fun 현재_배송지는_후보에_포함될_수_없다() {
        assertFailsWith<IllegalArgumentException> { context(1, listOf(1, 2), emptyMap()) }
    }

    @Test
    fun 이동시간은_음수일_수_없다() {
        assertFailsWith<IllegalArgumentException> { TravelCostMatrix(mapOf(leg(1, 2) to -1L)) }
    }

    @Test
    fun 이동시간이_0인_구간도_경로에_포함한다() {
        val result = optimizer.optimize(context(0, listOf(1, 2), mapOf(leg(0, 1) to 0L, leg(1, 2) to 0L)))
        assertEquals(listOf(1L, 2L), result.stopIds)
        assertEquals(0L, result.totalDurationSeconds)
        assertEquals(3, result.expandedStateCount)
    }

    @Test
    fun 총_이동시간이_Long_범위를_초과하면_실패한다() {
        assertFailsWith<ArithmeticException> {
            optimizer.optimize(context(0, listOf(1, 2), mapOf(leg(0, 1) to Long.MAX_VALUE - 1, leg(1, 2) to 2L)))
        }
    }

    @Test
    fun 경로_데이터는_입력_변경과_반환_컬렉션_변경에_영향받지_않는다() {
        val candidates = mutableListOf(1L)
        val durations = mutableMapOf(leg(0, 1) to 5L)
        val matrix = TravelCostMatrix(durations)
        val context = RouteOptimizationContext(0, candidates, matrix)
        val order = mutableListOf(1L)
        val route = OptimizedRoute(order, 5, 2)
        candidates += 2L
        durations[leg(0, 1)] = 50L
        order += 2L

        assertEquals(listOf(1L), context.candidateStopIds)
        assertEquals(5L, matrix.findDuration(0, 1))
        assertEquals(listOf(1L), route.stopIds)
        val addedOrder = route.stopIds.adding(3L)
        val changedMatrix = matrix.travelDurationSeconds.putting(leg(0, 1), 10L)
        assertEquals(listOf(1L, 3L), addedOrder)
        assertEquals(10L, changedMatrix[leg(0, 1)])
        assertEquals(listOf(1L), route.stopIds)
        assertEquals(5L, matrix.findDuration(0, 1))
        assertEquals(route, optimizer.optimize(context))
    }

    @Test
    fun 경로_데이터는_값_동등성과_해시_계약을_유지한다() {
        val matrix = TravelCostMatrix(mapOf(leg(0, 1) to 5L))
        val equivalent = TravelCostMatrix(mapOf(leg(0, 1) to 5L))
        val context = RouteOptimizationContext(0, listOf(1L), matrix)
        val route = OptimizedRoute(listOf(1L), 5, 2)
        assertEquals(leg(0, 1), leg(0, 1))
        assertEquals(matrix, equivalent)
        assertEquals(matrix.hashCode(), equivalent.hashCode())
        assertNull(matrix.findDuration(1, 2))
        assertEquals(context, context.copy())
        assertEquals(context.hashCode(), context.copy().hashCode())
        assertEquals(route, route.copy())
        assertEquals(route.hashCode(), route.copy().hashCode())
        assertNotEquals(route, route.copy(totalDurationSeconds = 6))
    }

    private fun leg(from: Long, to: Long) = RouteLeg(from, to)
    private fun context(current: Long, candidates: List<Long>, durations: Map<RouteLeg, Long>) =
        RouteOptimizationContext(current, candidates, TravelCostMatrix(durations))
}
