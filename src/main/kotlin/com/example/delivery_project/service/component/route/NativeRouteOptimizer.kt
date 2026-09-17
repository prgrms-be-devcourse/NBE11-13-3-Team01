package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.route.nativebridge.RouteOptimizerNative

class NativeRouteOptimizer(
    private val fallback: RouteOptimizer = BitmaskDpRouteOptimizer(),
) : RouteOptimizer {

    val usesNative: Boolean get() = RouteOptimizerNative.isAvailable()

    override fun optimize(context: RouteOptimizationContext): OptimizedRoute {
        if (!RouteOptimizerNative.isAvailable()) {
            return fallback.optimize(context)
        }

        val matrix = RouteMatrix.from(context)
        if (matrix.candidateCount == 0) {
            return OptimizedRoute(emptyList(), 0L, 1)
        }

        val result = RouteOptimizerNative.optimize(matrix.nodeCount, matrix.costs)
        return when (result.status()) {
            RouteOptimizerNative.STATUS_OK ->
                OptimizedRoute(
                    matrix.toStopIds(result.route(), matrix.candidateCount),
                    result.totalDuration(),
                    result.reachableStates().toInt(),
                )
            RouteOptimizerNative.STATUS_NO_ROUTE ->
                throw BusinessException(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE)

            RouteOptimizerNative.STATUS_OVERFLOW ->
                throw ArithmeticException("이동시간 합이 long 범위를 넘었습니다.")
            RouteOptimizerNative.STATUS_INVALID_ARGUMENT ->
                throw IllegalStateException("네이티브 호출 인자가 잘못됐습니다. (nodeCount=${matrix.nodeCount})")

            RouteOptimizerNative.STATUS_OUT_OF_MEMORY ->
                throw IllegalStateException("네이티브 작업 메모리 할당에 실패했습니다.")

            else ->
                throw IllegalStateException("알 수 없는 네이티브 status: ${result.status()}")
        }
    }
}
