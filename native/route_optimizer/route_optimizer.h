/*
 * 배송 경로 최적화 — 네이티브 구현.
 *
 * Kotlin 구현(DijkstraRouteOptimizer, BitmaskDpRouteOptimizer)과 **같은 문제**를 푼다.
 * 시작 노드에서 출발해 모든 후보를 한 번씩 방문하는 최소 이동시간 Hamiltonian path.
 * 출발점으로 돌아오지 않는다.
 *
 * 이 헤더가 곧 ABI 계약이다. 아래 항목은 Kotlin 쪽과 글자 그대로 일치해야 한다.
 *
 *  - 노드 번호        : 0 = 현재 위치(시작), 1..node_count-1 = 후보. 호출자가 재매핑한다.
 *                       (Kotlin 의 stop id 는 Long.MIN_VALUE 같은 값을 쓰므로 그대로 넘기면 안 된다.)
 *  - 비용 행렬        : row-major 평탄 배열. cost[i * node_count + j] = i→j 이동시간(초).
 *  - 간선 없음        : **음수**. Kotlin TravelCostMatrix 가 음수 이동시간을 이미 금지하므로
 *                       -1 은 안전하게 비어 있는 값이다. INT64_MAX 를 sentinel 로 쓰면
 *                       더하는 순간 오버플로가 나므로 쓰지 않는다.
 *  - 대각선           : 사용하지 않는다. 무엇이 들어 있든 무시한다.
 *  - 동점 처리        : 최소 비용 경로가 여러 개면 **노드 번호 수열이 사전순으로 가장 작은 것**.
 *                       이 규칙이 세 구현에 모두 박혀 있어야 differential test 가 경로까지 비교할 수 있다.
 *  - 오버플로         : 비용 합이 int64 를 넘으면 ROUTE_STATUS_OVERFLOW.
 *                       Kotlin 의 Math.addExact 와 같은 자리에서 잡는다.
 */
#ifndef ROUTE_OPTIMIZER_H
#define ROUTE_OPTIMIZER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** 시작 노드를 포함한 최대 노드 수. 후보 16개까지. */
#define ROUTE_MAX_NODE_COUNT 17

enum {
    ROUTE_STATUS_OK = 0,
    /** node_count 범위를 벗어났거나 포인터가 NULL 이다. */
    ROUTE_STATUS_INVALID_ARGUMENT = 1,
    /** 모든 후보를 방문하는 경로가 존재하지 않는다. */
    ROUTE_STATUS_NO_ROUTE = 2,
    /** 비용 합이 int64 를 넘었다. */
    ROUTE_STATUS_OVERFLOW = 3,
    /** 내부 작업 메모리 할당 실패. */
    ROUTE_STATUS_OUT_OF_MEMORY = 4
};

typedef struct {
    int32_t status;
    /** 방문 순서의 길이. 성공 시 node_count - 1. */
    int32_t route_length;
    /** 총 이동시간(초). 성공 시에만 의미가 있다. */
    int64_t total_duration;
    /**
     * DP 가 도달 가능하다고 판정한 (방문집합, 현재노드) 상태 수.
     *
     * 주의: Dijkstra 구현의 expandedStateCount 와 **정의가 다르다.**
     * 성능 관찰용이며 구현 간 동등성 비교 대상이 아니다.
     */
    int64_t reachable_states;
} route_result;

/**
 * @param node_count   시작 노드 포함 노드 수. 1..ROUTE_MAX_NODE_COUNT.
 * @param cost_matrix  node_count * node_count 개의 int64. 음수 = 간선 없음.
 * @param out_route    방문 순서를 쓸 버퍼. 최소 node_count - 1 칸.
 *                     노드 번호(1..node_count-1)가 들어간다. 실패 시 내용은 정의되지 않는다.
 */
route_result route_optimize(int32_t node_count, const int64_t *cost_matrix, int32_t *out_route);

/** 빌드 확인용. ABI 가 살아 있는지 한 번에 보려고 둔다. */
int32_t route_optimizer_abi_version(void);

#ifdef __cplusplus
}
#endif

#endif /* ROUTE_OPTIMIZER_H */
