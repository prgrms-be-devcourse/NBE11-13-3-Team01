#include "route_optimizer.h"

#include <stdlib.h>
#include <string.h>

#define INF INT64_MAX

static int64_t cost_at(const int64_t *matrix, int32_t node_count, int32_t from, int32_t to) {
    return matrix[(size_t)from * (size_t)node_count + (size_t)to];
}

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

    for (int32_t last = 0; last < node_count; last++) {
        remaining[state_index(full_mask, last, node_count)] = 0;
    }

    int overflowed = 0;


    for (int32_t mask = full_mask - 1; mask >= 0; mask--) {
        for (int32_t last = 0; last < node_count; last++) {

            if (last > 0 && ((mask >> (last - 1)) & 1) == 0) {
                continue;
            }

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
                    continue;
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
        result.status = overflowed ? ROUTE_STATUS_OVERFLOW : ROUTE_STATUS_NO_ROUTE;
        return result;
    }

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
                break;
            }
        }
        if (chosen < 0) {
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
