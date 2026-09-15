package com.example.delivery_project.service.component.route

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.route.nativebridge.RouteOptimizerNative

/**
 * C 로 구현한 bitmask DP 를 FFM 으로 호출하는 [RouteOptimizer].
 *
 * **운영 기본 구현이 아니다.** 후보가 5개인 현재 규모에서는 DP 자체보다 FFI 마샬링이
 * 더 비싸고, 요청 전체 시간은 DB 조회와 외부 길찾기 호출이 지배한다.
 * 이 구현의 용도는 (1) 언어 경계 학습과 (2) 후보 수를 늘렸을 때 어디서 역전되는지
 * 측정하는 것이다. 그래서 Spring 빈으로 등록하지 않는다.
 *
 * [BitmaskDpRouteOptimizer] 와 **완전히 같은 알고리즘, 같은 동점 처리 규칙**을 쓴다.
 * 둘의 결과가 총비용뿐 아니라 경로 순서까지 같아야 한다는 것이 differential test 의 기준이다.
 *
 * @param fallback 네이티브 라이브러리를 쓸 수 없을 때 대신 쓸 구현.
 *                 빌드하지 않은 환경에서도 동작해야 하므로 예외를 던지지 않는다.
 */
class NativeRouteOptimizer(
    private val fallback: RouteOptimizer = BitmaskDpRouteOptimizer(),
) : RouteOptimizer {

    /** 실제로 네이티브를 쓰고 있는지. 벤치마크와 테스트가 확인한다. */
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

            // 아래 두 가지는 Kotlin 구현이 같은 입력에서 내는 것과 같은 실패로 맞춘다.
            RouteOptimizerNative.STATUS_NO_ROUTE ->
                throw BusinessException(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE)

            RouteOptimizerNative.STATUS_OVERFLOW ->
                throw ArithmeticException("이동시간 합이 long 범위를 넘었습니다.")

            // 아래는 호출자 버그이거나 환경 문제다. 조용히 폴백하면 원인을 놓친다.
            RouteOptimizerNative.STATUS_INVALID_ARGUMENT ->
                throw IllegalStateException("네이티브 호출 인자가 잘못됐습니다. (nodeCount=${matrix.nodeCount})")

            RouteOptimizerNative.STATUS_OUT_OF_MEMORY ->
                throw IllegalStateException("네이티브 작업 메모리 할당에 실패했습니다.")

            else ->
                throw IllegalStateException("알 수 없는 네이티브 status: ${result.status()}")
        }
    }
}
