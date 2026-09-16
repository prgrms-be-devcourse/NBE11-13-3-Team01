#define _POSIX_C_SOURCE 200809L

#include "route_optimizer.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <unistd.h>

#define DEFAULT_PORT 8091
#define MAX_BODY_BYTES (1024u * 1024u)

typedef struct {
    int client_fd;
} client_arg;

static const char *skip_space(const char *p, const char *end) {
    while (p < end && isspace((unsigned char)*p)) p++;
    return p;
}

static const char *find_key(const char *body, size_t length, const char *key) {
    char quoted[64];
    const int written = snprintf(quoted, sizeof(quoted), "\"%s\"", key);
    if (written < 0 || (size_t)written >= sizeof(quoted)) return NULL;
    for (size_t i = 0; i + (size_t)written <= length; i++) {
        if (memcmp(body + i, quoted, (size_t)written) == 0) return body + i + written;
    }
    return NULL;
}

static int parse_i64(const char **cursor, const char *end, int64_t *out) {
    char *next = NULL;
    const char *start = skip_space(*cursor, end);
    if (start >= end) return 0;
    errno = 0;
    const long long value = strtoll(start, &next, 10);
    if (next == start || errno == ERANGE || next > end) return 0;
    *out = (int64_t)value;
    *cursor = next;
    return 1;
}

static int parse_request(const char *body, size_t length, int32_t *node_count, int64_t **costs) {
    const char *node_key = find_key(body, length, "nodeCount");
    const char *cost_key = find_key(body, length, "costs");
    const char *end = body + length;
    int64_t node_value = 0;
    if (node_key == NULL || cost_key == NULL) return 0;
    node_key = skip_space(node_key, end);
    if (node_key >= end || *node_key != ':') return 0;
    node_key++;
    if (!parse_i64(&node_key, end, &node_value) || node_value < 1 || node_value > ROUTE_MAX_NODE_COUNT) return 0;

    const int32_t count = (int32_t)node_value;
    const size_t matrix_size = (size_t)count * (size_t)count;
    int64_t *matrix = (int64_t *)calloc(matrix_size, sizeof(int64_t));
    if (matrix == NULL) return 0;
    cost_key = skip_space(cost_key, end);
    if (cost_key >= end || *cost_key != ':') { free(matrix); return 0; }
    cost_key = skip_space(cost_key + 1, end);
    if (cost_key >= end || *cost_key != '[') { free(matrix); return 0; }
    cost_key++;
    for (size_t i = 0; i < matrix_size; i++) {
        if (!parse_i64(&cost_key, end, &matrix[i])) { free(matrix); return 0; }
        cost_key = skip_space(cost_key, end);
        if (i + 1 < matrix_size) {
            if (cost_key >= end || *cost_key != ',') { free(matrix); return 0; }
            cost_key++;
        }
    }
    cost_key = skip_space(cost_key, end);
    if (cost_key >= end || *cost_key != ']') { free(matrix); return 0; }
    *node_count = count;
    *costs = matrix;
    return 1;
}

static int send_all(int fd, const char *data, size_t length) {
    size_t sent = 0;
    while (sent < length) {
        const ssize_t written = send(fd, data + sent, length - sent, 0);
        if (written <= 0) return 0;
        sent += (size_t)written;
    }
    return 1;
}

static void send_json(int fd, int status_code, const char *json) {
    char header[256];
    const int length = snprintf(
        header, sizeof(header),
        "HTTP/1.1 %d %s\r\nContent-Type: application/json\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n",
        status_code, status_code == 200 ? "OK" : "Bad Request", strlen(json));
    if (length > 0 && (size_t)length < sizeof(header)) {
        send_all(fd, header, (size_t)length);
        send_all(fd, json, strlen(json));
    }
}

static void *handle_client(void *arg) {
    client_arg *client = (client_arg *)arg;
    const int fd = client->client_fd;
    free(client);
    char *request = (char *)calloc(MAX_BODY_BYTES + 1u, 1u);
    if (request == NULL) { close(fd); return NULL; }
    size_t used = 0;
    ssize_t received;
    while (used < MAX_BODY_BYTES) {
        received = recv(fd, request + used, MAX_BODY_BYTES - used, 0);
        if (received <= 0) { free(request); close(fd); return NULL; }
        used += (size_t)received;
        request[used] = '\0';
        if (strstr(request, "\r\n\r\n") != NULL) break;
    }
    char *body = strstr(request, "\r\n\r\n");
    if (body == NULL) { send_json(fd, 400, "{\"status\":1}"); free(request); close(fd); return NULL; }
    body += 4;
    if (strncmp(request, "POST /optimize ", 15) != 0) {
        send_json(fd, 404, "{\"status\":1}");
        free(request); close(fd); return NULL;
    }
    const char *content_length_header = NULL;
    for (const char *header = request; header + 15 <= body; header++) {
        if (strncasecmp(header, "Content-Length:", 15) == 0) {
            content_length_header = header;
            break;
        }
    }
    if (content_length_header == NULL) { send_json(fd, 400, "{\"status\":1}"); free(request); close(fd); return NULL; }
    long body_length_value = strtol(content_length_header + 15, NULL, 10);
    if (body_length_value < 0 || (unsigned long)body_length_value > MAX_BODY_BYTES) { send_json(fd, 400, "{\"status\":1}"); free(request); close(fd); return NULL; }
    const size_t body_length = (size_t)body_length_value;
    size_t body_received = used - (size_t)(body - request);
    while (body_received < body_length && used < MAX_BODY_BYTES) {
        received = recv(fd, request + used, MAX_BODY_BYTES - used, 0);
        if (received <= 0) break;
        used += (size_t)received;
        body_received += (size_t)received;
    }
    if (body_received < body_length) { send_json(fd, 400, "{\"status\":1}"); free(request); close(fd); return NULL; }

    int32_t node_count = 0;
    int64_t *costs = NULL;
    int32_t route[ROUTE_MAX_NODE_COUNT - 1];
    route_result result;
    if (!parse_request(body, body_length, &node_count, &costs)) {
        send_json(fd, 400, "{\"status\":1,\"routeLength\":0,\"totalDuration\":0,\"reachableStates\":0,\"route\":[]}");
        free(request); close(fd); return NULL;
    }
    result = route_optimize(node_count, costs, route);
    free(costs);
    char json[2048];
    int offset = snprintf(json, sizeof(json), "{\"status\":%d,\"routeLength\":%d,\"totalDuration\":%lld,\"reachableStates\":%lld,\"route\":[", result.status, result.route_length, (long long)result.total_duration, (long long)result.reachable_states);
    if (offset < 0) offset = 0;
    for (int32_t i = 0; i < result.route_length && offset < (int)sizeof(json); i++) {
        offset += snprintf(json + offset, sizeof(json) - (size_t)offset, "%s%d", i == 0 ? "" : ",", route[i]);
    }
    snprintf(json + (offset < (int)sizeof(json) ? offset : (int)sizeof(json) - 1), sizeof(json) - (size_t)(offset < (int)sizeof(json) ? offset : (int)sizeof(json) - 1), "]}");
    send_json(fd, 200, json);
    free(request);
    close(fd);
    return NULL;
}

int main(void) {
    const char *port_env = getenv("ROUTE_OPTIMIZER_PORT");
    const int port = port_env == NULL ? DEFAULT_PORT : atoi(port_env);
    signal(SIGPIPE, SIG_IGN);
    const int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("route optimizer socket");
        return 1;
    }
    int reuse = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons((uint16_t)port);
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        perror("route optimizer bind");
        close(server_fd);
        return 1;
    }
    if (listen(server_fd, 64) < 0) {
        perror("route optimizer listen");
        close(server_fd);
        return 1;
    }
    fprintf(stdout, "route optimizer server listening on %d\n", port);
    for (;;) {
        const int client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) continue;
        client_arg *arg = (client_arg *)malloc(sizeof(client_arg));
        if (arg == NULL) { close(client_fd); continue; }
        arg->client_fd = client_fd;
        pthread_t thread;
        if (pthread_create(&thread, NULL, handle_client, arg) == 0) pthread_detach(thread);
        else { free(arg); close(client_fd); }
    }
}
