#define _POSIX_C_SOURCE 200809L

#include "route_optimizer.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define DEFAULT_PORT 8092
#define MAX_PACKET_BYTES 65507u

static int parse_request(const char *body, size_t length, int32_t *node_count, int64_t **costs) {
    const char *node_key = strstr(body, "\"nodeCount\"");
    const char *cost_key = strstr(body, "\"costs\"");
    if (node_key == NULL || cost_key == NULL || node_key >= body + length || cost_key >= body + length) return 0;
    node_key = strchr(node_key, ':');
    cost_key = strchr(cost_key, '[');
    if (node_key == NULL || cost_key == NULL) return 0;
    char *next = NULL;
    errno = 0;
    const long node_value = strtol(node_key + 1, &next, 10);
    if (errno != 0 || next == node_key + 1 || node_value < 1 || node_value > ROUTE_MAX_NODE_COUNT) return 0;
    const int32_t count = (int32_t)node_value;
    const size_t matrix_size = (size_t)count * (size_t)count;
    int64_t *matrix = calloc(matrix_size, sizeof(int64_t));
    if (matrix == NULL) return 0;
    const char *cursor = cost_key + 1;
    for (size_t i = 0; i < matrix_size; i++) {
        errno = 0;
        long long value = strtoll(cursor, &next, 10);
        if (errno != 0 || next == cursor) { free(matrix); return 0; }
        matrix[i] = (int64_t)value;
        cursor = next;
        while (*cursor == ' ' || *cursor == '\t' || *cursor == '\n' || *cursor == '\r' || *cursor == ',') cursor++;
    }
    *node_count = count;
    *costs = matrix;
    return 1;
}

static size_t make_response(const char *request, size_t request_length, char *json, size_t json_size) {
    int32_t node_count = 0;
    int64_t *costs = NULL;
    int32_t route[ROUTE_MAX_NODE_COUNT - 1];
    if (!parse_request(request, request_length, &node_count, &costs)) {
        return (size_t)snprintf(json, json_size, "{\"status\":1,\"routeLength\":0,\"totalDuration\":0,\"reachableStates\":0,\"route\":[]}");
    }
    const route_result result = route_optimize(node_count, costs, route);
    free(costs);
    int offset = snprintf(json, json_size, "{\"status\":%d,\"routeLength\":%d,\"totalDuration\":%lld,\"reachableStates\":%lld,\"route\":[", result.status, result.route_length, (long long)result.total_duration, (long long)result.reachable_states);
    for (int32_t i = 0; i < result.route_length && offset > 0 && (size_t)offset < json_size; i++) {
        offset += snprintf(json + offset, json_size - (size_t)offset, "%s%d", i == 0 ? "" : ",", route[i]);
    }
    if (offset > 0 && (size_t)offset < json_size) offset += snprintf(json + offset, json_size - (size_t)offset, "]}");
    return offset > 0 ? (size_t)offset : 0u;
}

int main(void) {
    const char *port_env = getenv("ROUTE_OPTIMIZER_UDP_PORT");
    const int port = port_env == NULL ? DEFAULT_PORT : atoi(port_env);
    signal(SIGPIPE, SIG_IGN);
    const int server_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (server_fd < 0) { perror("route optimizer udp socket"); return 1; }
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons((uint16_t)port);
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        perror("route optimizer udp bind"); close(server_fd); return 1;
    }
    fprintf(stdout, "route optimizer UDP server listening on %d\n", port);
    for (;;) {
        char request[MAX_PACKET_BYTES];
        char response[2048];
        struct sockaddr_in client = {0};
        socklen_t client_length = sizeof(client);
        const ssize_t received = recvfrom(server_fd, request, sizeof(request), 0, (struct sockaddr *)&client, &client_length);
        if (received <= 0) continue;
        const size_t response_length = make_response(request, (size_t)received, response, sizeof(response));
        if (response_length > 0) sendto(server_fd, response, response_length, 0, (struct sockaddr *)&client, client_length);
    }
}
