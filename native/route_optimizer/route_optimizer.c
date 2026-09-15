#include "route_optimizer.h"

#include <stdlib.h>
#include <string.h>

/*
 * Held-Karp 형태의 bitmask DP.
 *
 * Kotlin 의 Dijkstra 구현은 상태 우선순위 큐로 같은 답을 구하지만, 여기서는
 *   f[mask][last] = mask 에 없는 후보를 전부 방문하는 데 드는 **남은** 최소 비용
 * 을 뒤에서부터 채운다. 앞에서부터 채우지 않는 이유는 동점 처리 때문이다.
 *
 * 앞에서부터 채우고 parent 포인터로 되짚으면 "최소 비용 경로 중 하나"만 나오고,
 * 그게 어느 것인지는 갱신 순서에 달린다. 반면 f 를 먼저 다 구해 두면
 * 앞에서부터 걸어가며 매 단계 "최적을 유지하는 후보 중 번호가 가장 작은 것"을
 * 고를 수 있다. 그 결과가 곧 사전순 최소 경로이고, 이건 순서에 의존하지 않는다.
 */

#define INF INT64_MAX

static int64_t cost_at(const int64_t *matrix, int32_t node_count, int32_t from, int32_t to) {
    return matrix[(size_t)from * (size_t)node_count + (size_t)to];
}

/* (방문집합, 현재노드) 를 평탄 배열 첨자로. 부호 변환 경고를 한곳에 모아 둔다. */
static size_t state_index(int32_t mask, int32_t last, int32_t node_count) {
    return (size_t)mask * (size_t)node_count + (size_t)last;
}

int32_t route_optimizer_abi_version(void) {
    return 1;
}

route_result route_optimize(int32_t node_count, const int64_t *cost_matrix, int32_t *out_route) {
    route_result result;
    result.status = ROUTE_STATUS_INVALID_ARGUMENT;
    result.route_length = 0;
    result.total_duration = 0;
    result.reachable_states = 0;

    if (node_count < 1 || node_count > ROUTE_MAX_NODE_COUNT) {
        return result;
    }
    if (cost_matrix == NULL) {
        return result;
    }

    const int32_t candidate_count = node_count - 1;
    if (candidate_count > 0 && out_route == NULL) {
        return result;
    }

    /* 후보가 없으면 시작 노드에 머무는 빈 경로가 정답이다.
       Kotlin 구현도 같은 입력에서 빈 경로와 0 을 돌려준다. */
    if (candidate_count == 0) {
        result.status = ROUTE_STATUS_OK;
        result.route_length = 0;
        result.total_duration = 0;
        result.reachable_states = 1;
        return result;
    }

    const int32_t full_mask = (int32_t)((1u << candidate_count) - 1u);
    const size_t state_count = (size_t)(full_mask + 1) * (size_t)node_count;

    int64_t *remaining = (int64_t *)malloc(state_count * sizeof(int64_t));
    if (remaining == NULL) {
        result.status = ROUTE_STATUS_OUT_OF_MEMORY;
        return result;
    }

    for (size_t i = 0; i < state_count; i++) {
        remaining[i] = INF;
    }
    /* 전부 방문한 상태는 어디에 서 있든 추가 비용이 0 이다. */
    for (int32_t last = 0; last < node_count; last++) {
        remaining[state_index(full_mask, last, node_count)] = 0;
    }

    int overflowed = 0;

    /* mask 를 내림차순으로 훑으면 mask | bit 가 항상 먼저 채워져 있다. */
    for (int32_t mask = full_mask - 1; mask >= 0; mask--) {
        for (int32_t last = 0; last < node_count; last++) {
            /* last 가 후보인데 아직 mask 에 없다면 도달할 수 없는 조합이다. */
            if (last > 0 && ((mask >> (last - 1)) & 1) == 0) {
                continue;
            }
            /* 시작 노드에 서 있을 수 있는 건 아무것도 방문하지 않은 시점뿐이다. */
            if (last == 0 && mask != 0) {
                continue;
            }

            int64_t best = INF;
            for (int32_t candidate = 0; candidate < candidate_count; candidate++) {
                if ((mask >> candidate) & 1) {
                    continue;
                }
                const int32_t next_node = candidate + 1;
                const int64_t edge = cost_at(cost_matrix, node_count, last, next_node);
                if (edge < 0) {
                    continue; /* 간선 없음 */
                }
                const int64_t tail = remaining[state_index(mask | (1 << candidate), next_node, node_count)];
                if (tail == INF) {
                    continue;
                }
                int64_t total;
                if (__builtin_add_overflow(edge, tail, &total)) {
                    overflowed = 1;
                    continue;
                }
                if (total < best) {
                    best = total;
                }
            }
            remaining[state_index(mask, last, node_count)] = best;
            if (best != INF) {
                result.reachable_states++;
            }
        }
    }

    const int64_t optimum = remaining[state_index(0, 0, node_count)];
    if (optimum == INF) {
        free(remaining);
        /* 오버플로 때문에 모든 후보가 잘려 나갔다면 "경로 없음"이 아니라 오버플로다. */
        result.status = overflowed ? ROUTE_STATUS_OVERFLOW : ROUTE_STATUS_NO_ROUTE;
        return result;
    }

    /* 앞에서부터 걸으며 최적을 유지하는 후보 중 번호가 가장 작은 것을 고른다. */
    int32_t mask = 0;
    int32_t current = 0;
    int64_t left = optimum;
    for (int32_t step = 0; step < candidate_count; step++) {
        int32_t chosen = -1;
        for (int32_t candidate = 0; candidate < candidate_count; candidate++) {
            if ((mask >> candidate) & 1) {
                continue;
            }
            const int32_t next_node = candidate + 1;
            const int64_t edge = cost_at(cost_matrix, node_count, current, next_node);
            if (edge < 0) {
                continue;
            }
            const int64_t tail = remaining[state_index(mask | (1 << candidate), next_node, node_count)];
            if (tail == INF) {
                continue;
            }
            int64_t total;
            if (__builtin_add_overflow(edge, tail, &total)) {
                continue;
            }
            if (total == left) {
                chosen = candidate;
                break; /* 오름차순으로 훑으므로 처음 맞는 것이 사전순 최소다 */
            }
        }
        if (chosen < 0) {
            /* f 를 제대로 채웠다면 도달할 수 없는 분기다. 방어적으로 막아 둔다. */
            free(remaining);
            result.status = ROUTE_STATUS_NO_ROUTE;
            result.route_length = 0;
            return result;
        }
        const int32_t next_node = chosen + 1;
        left -= cost_at(cost_matrix, node_count, current, next_node);
        mask |= (1 << chosen);
        current = next_node;
        out_route[step] = next_node;
    }

    free(remaining);
    result.status = ROUTE_STATUS_OK;
    result.route_length = candidate_count;
    result.total_duration = optimum;
    return result;
}
