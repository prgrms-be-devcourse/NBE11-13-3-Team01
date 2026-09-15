/*
 * route_optimize 를 **완전탐색과 대조**해 검증한다.
 *
 * DP 가 스스로를 검증할 수는 없으므로, 후보 수가 작을 때(n <= 8) 모든 순열을
 * 사전순으로 훑어 최소 비용과 그 비용을 내는 첫 순열을 구한 뒤 DP 결과와 맞춰 본다.
 * "사전순으로 훑으며 더 나을 때만 갱신"하면 그 결과가 곧 사전순 최소 경로다.
 */
#include "route_optimizer.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_N 8

static int failures = 0;
static int checks = 0;

static void fail(const char *what) {
    printf("  FAIL: %s\n", what);
    failures++;
}

/* --- 작은 xorshift. 플랫폼 rand() 에 의존하지 않아 재현된다. --- */
static uint64_t rng_state = 88172645463325252u;
static uint64_t next_random(void) {
    rng_state ^= rng_state << 13;
    rng_state ^= rng_state >> 7;
    rng_state ^= rng_state << 17;
    return rng_state;
}
static int64_t random_in(int64_t low, int64_t high) {
    return low + (int64_t)(next_random() % (uint64_t)(high - low + 1));
}

/* --- 사전순 다음 순열. C 표준에 없어 직접 둔다. --- */
static int next_permutation(int32_t *array, int32_t length) {
    if (length < 2) {
        return 0;
    }
    int32_t i = length - 2;
    while (i >= 0 && array[i] >= array[i + 1]) {
        i--;
    }
    if (i < 0) {
        return 0;
    }
    int32_t j = length - 1;
    while (array[j] <= array[i]) {
        j--;
    }
    int32_t swap = array[i];
    array[i] = array[j];
    array[j] = swap;
    for (int32_t left = i + 1, right = length - 1; left < right; left++, right--) {
        swap = array[left];
        array[left] = array[right];
        array[right] = swap;
    }
    return 1;
}

typedef struct {
    int found;
    int64_t total;
    int32_t route[MAX_N];
} brute_result;

/* 모든 순열을 사전순으로 훑어 최소 비용과 사전순 최소 경로를 구한다. */
static brute_result brute_force(int32_t node_count, const int64_t *cost) {
    brute_result best;
    best.found = 0;
    best.total = 0;
    memset(best.route, 0, sizeof(best.route));

    const int32_t candidate_count = node_count - 1;
    if (candidate_count == 0) {
        best.found = 1;
        return best;
    }

    int32_t order[MAX_N];
    for (int32_t i = 0; i < candidate_count; i++) {
        order[i] = i + 1;
    }

    do {
        int64_t total = 0;
        int32_t current = 0;
        int broken = 0;
        for (int32_t step = 0; step < candidate_count; step++) {
            const int64_t edge = cost[(size_t)current * (size_t)node_count + (size_t)order[step]];
            if (edge < 0) {
                broken = 1;
                break;
            }
            if (edge > INT64_MAX - total) {
                broken = 1; /* 오버플로 경로는 후보에서 제외 */
                break;
            }
            total += edge;
            current = order[step];
        }
        if (!broken && (!best.found || total < best.total)) {
            best.found = 1;
            best.total = total;
            memcpy(best.route, order, sizeof(int32_t) * (size_t)candidate_count);
        }
    } while (next_permutation(order, candidate_count));

    return best;
}

static void compare(int32_t node_count, const int64_t *cost, const char *label) {
    checks++;
    const brute_result expected = brute_force(node_count, cost);

    int32_t actual_route[ROUTE_MAX_NODE_COUNT];
    memset(actual_route, 0, sizeof(actual_route));
    const route_result actual = route_optimize(node_count, cost, actual_route);

    if (!expected.found) {
        if (actual.status != ROUTE_STATUS_NO_ROUTE && actual.status != ROUTE_STATUS_OVERFLOW) {
            printf("  [%s] 완주 경로가 없어야 하는데 status=%d\n", label, actual.status);
            fail(label);
        }
        return;
    }
    if (actual.status != ROUTE_STATUS_OK) {
        printf("  [%s] status=%d (기대 OK)\n", label, actual.status);
        fail(label);
        return;
    }
    if (actual.total_duration != expected.total) {
        printf("  [%s] 총비용 %" PRId64 " != 완전탐색 %" PRId64 "\n",
               label, actual.total_duration, expected.total);
        fail(label);
        return;
    }
    if (actual.route_length != node_count - 1) {
        printf("  [%s] 경로 길이 %d != %d\n", label, actual.route_length, node_count - 1);
        fail(label);
        return;
    }
    for (int32_t i = 0; i < node_count - 1; i++) {
        if (actual_route[i] != expected.route[i]) {
            printf("  [%s] 경로 불일치 (사전순 최소여야 함). 위치 %d: %d != %d\n",
                   label, i, actual_route[i], expected.route[i]);
            fail(label);
            return;
        }
    }
}

/* 무작위 비용 행렬. missing_percent 만큼 간선을 끊는다. */
static void random_matrix(int64_t *cost, int32_t node_count, int64_t high, int missing_percent) {
    for (int32_t i = 0; i < node_count; i++) {
        for (int32_t j = 0; j < node_count; j++) {
            if (i == j) {
                cost[(size_t)i * (size_t)node_count + (size_t)j] = -1;
                continue;
            }
            const int drop = (int)(next_random() % 100u) < missing_percent;
            cost[(size_t)i * (size_t)node_count + (size_t)j] = drop ? -1 : random_in(0, high);
        }
    }
}

static void test_against_brute_force(void) {
    printf("완전탐색 대조\n");
    int64_t cost[ROUTE_MAX_NODE_COUNT * ROUTE_MAX_NODE_COUNT];

    /* 비용 범위를 좁히면 동점이 많이 생겨 tie-break 가 실제로 검증된다. */
    const int64_t highs[] = {1, 3, 10, 1000};
    const int missing[] = {0, 0, 15, 40, 70};

    for (int32_t node_count = 1; node_count <= MAX_N + 1; node_count++) {
        for (size_t h = 0; h < sizeof(highs) / sizeof(highs[0]); h++) {
            for (size_t m = 0; m < sizeof(missing) / sizeof(missing[0]); m++) {
                const int iterations = node_count <= 6 ? 120 : 25;
                for (int iteration = 0; iteration < iterations; iteration++) {
                    random_matrix(cost, node_count, highs[h], missing[m]);
                    char label[96];
                    snprintf(label, sizeof(label), "n=%d high=%" PRId64 " missing=%d%% #%d",
                             node_count - 1, highs[h], missing[m], iteration);
                    compare(node_count, cost, label);
                }
            }
        }
    }
    printf("  %d개 조합 대조 완료\n", checks);
}

static void test_edge_cases(void) {
    printf("경계 조건\n");
    int64_t cost[ROUTE_MAX_NODE_COUNT * ROUTE_MAX_NODE_COUNT];
    int32_t route[ROUTE_MAX_NODE_COUNT];

    /* 후보 없음 */
    cost[0] = -1;
    route_result r = route_optimize(1, cost, route);
    if (r.status != ROUTE_STATUS_OK || r.route_length != 0 || r.total_duration != 0) {
        fail("후보 0개는 빈 경로 + 0 이어야 한다");
    }

    /* 인자 검증 */
    if (route_optimize(0, cost, route).status != ROUTE_STATUS_INVALID_ARGUMENT) {
        fail("node_count=0 은 INVALID_ARGUMENT");
    }
    if (route_optimize(ROUTE_MAX_NODE_COUNT + 1, cost, route).status != ROUTE_STATUS_INVALID_ARGUMENT) {
        fail("상한 초과는 INVALID_ARGUMENT");
    }
    if (route_optimize(3, NULL, route).status != ROUTE_STATUS_INVALID_ARGUMENT) {
        fail("cost_matrix NULL 은 INVALID_ARGUMENT");
    }
    if (route_optimize(3, cost, NULL).status != ROUTE_STATUS_INVALID_ARGUMENT) {
        fail("out_route NULL 은 INVALID_ARGUMENT");
    }

    /* 완주 불가: 0 에서 아무 데도 못 간다 */
    for (int i = 0; i < 9; i++) {
        cost[i] = -1;
    }
    if (route_optimize(3, cost, route).status != ROUTE_STATUS_NO_ROUTE) {
        fail("끊어진 그래프는 NO_ROUTE");
    }

    /* 0 비용 간선만 있는 경우: 모든 경로가 동점이므로 사전순 최소가 나와야 한다 */
    for (int i = 0; i < 16; i++) {
        cost[i] = 0;
    }
    r = route_optimize(4, cost, route);
    if (r.status != ROUTE_STATUS_OK || r.total_duration != 0 ||
        route[0] != 1 || route[1] != 2 || route[2] != 3) {
        fail("전부 0 비용이면 1,2,3 순서여야 한다");
    }

    /* 유일한 경로가 오버플로 */
    const int32_t n = 3;
    for (int32_t i = 0; i < n * n; i++) {
        cost[i] = -1;
    }
    cost[0 * n + 1] = INT64_MAX - 1;
    cost[1 * n + 2] = 10;
    if (route_optimize(n, cost, route).status != ROUTE_STATUS_OVERFLOW) {
        fail("합이 int64 를 넘으면 OVERFLOW");
    }

    /* 한쪽만 오버플로면 멀쩡한 쪽이 답이다 */
    for (int32_t i = 0; i < n * n; i++) {
        cost[i] = -1;
    }
    cost[0 * n + 1] = INT64_MAX - 1;
    cost[1 * n + 2] = 10;
    cost[0 * n + 2] = 5;
    cost[2 * n + 1] = 7;
    r = route_optimize(n, cost, route);
    if (r.status != ROUTE_STATUS_OK || r.total_duration != 12 || route[0] != 2 || route[1] != 1) {
        printf("  status=%d total=%" PRId64 " route=%d,%d\n", r.status, r.total_duration, route[0], route[1]);
        fail("오버플로 경로만 버리고 나머지는 살려야 한다");
    }

    /* ABI 버전 */
    if (route_optimizer_abi_version() != 1) {
        fail("ABI 버전");
    }
    printf("  경계 조건 통과\n");
}

static void test_upper_bound(void) {
    printf("상한 크기 (후보 16개)\n");
    static int64_t cost[ROUTE_MAX_NODE_COUNT * ROUTE_MAX_NODE_COUNT];
    int32_t route[ROUTE_MAX_NODE_COUNT];
    random_matrix(cost, ROUTE_MAX_NODE_COUNT, 100000, 0);
    const route_result r = route_optimize(ROUTE_MAX_NODE_COUNT, cost, route);
    if (r.status != ROUTE_STATUS_OK || r.route_length != ROUTE_MAX_NODE_COUNT - 1) {
        fail("상한 크기에서 실패");
        return;
    }
    /* 반환 경로가 실제로 유효한 순열이고 비용이 맞는지 다시 확인한다. */
    int seen[ROUTE_MAX_NODE_COUNT] = {0};
    int64_t total = 0;
    int32_t current = 0;
    for (int32_t i = 0; i < r.route_length; i++) {
        const int32_t node = route[i];
        if (node < 1 || node >= ROUTE_MAX_NODE_COUNT || seen[node]) {
            fail("경로가 유효한 순열이 아니다");
            return;
        }
        seen[node] = 1;
        total += cost[(size_t)current * ROUTE_MAX_NODE_COUNT + (size_t)node];
        current = node;
    }
    if (total != r.total_duration) {
        fail("반환 경로의 실제 합이 total_duration 과 다르다");
        return;
    }
    printf("  총 %" PRId64 "초 / 도달 가능 상태 %" PRId64 "개\n", r.total_duration, r.reachable_states);
}

int main(void) {
    test_against_brute_force();
    test_edge_cases();
    test_upper_bound();

    printf("\n%s (검사 %d건, 실패 %d건)\n", failures == 0 ? "ALL PASS" : "FAILED", checks, failures);
    return failures == 0 ? 0 : 1;
}
