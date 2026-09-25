#ifndef CPLUS_STDLIB_HTTP_SERVER_CP
#define CPLUS_STDLIB_HTTP_SERVER_CP

#include <stddef.h>
#include <stdio.h>
#include <string.h>

comptime import "stdlib:/net/socket.cp";
comptime import "stdlib:/http/protocol.cp";
comptime import "stdlib:/concurrency/thread_pool.cp";

#ifndef CPLUS_HTTP_SERVER_MAX_CONNECTIONS
#define CPLUS_HTTP_SERVER_MAX_CONNECTIONS 16
#endif
#ifndef CPLUS_HTTP_SERVER_MAX_ROUTES
#define CPLUS_HTTP_SERVER_MAX_ROUTES 32
#endif
#ifndef CPLUS_HTTP_SERVER_REQUEST_CAPACITY
#define CPLUS_HTTP_SERVER_REQUEST_CAPACITY 8192
#endif
#ifndef CPLUS_HTTP_SERVER_RESPONSE_CAPACITY
#define CPLUS_HTTP_SERVER_RESPONSE_CAPACITY 8192
#endif
#ifndef CPLUS_HTTP_SERVER_IO_CHUNK
#define CPLUS_HTTP_SERVER_IO_CHUNK 1024
#endif
#ifndef CPLUS_HTTP_HANDLER_CONTEXT_BYTES
#define CPLUS_HTTP_HANDLER_CONTEXT_BYTES 256
#endif
#ifndef CPLUS_HTTP_ROUTE_METHOD_CAPACITY
#define CPLUS_HTTP_ROUTE_METHOD_CAPACITY 16
#endif
#ifndef CPLUS_HTTP_ROUTE_PATH_CAPACITY
#define CPLUS_HTTP_ROUTE_PATH_CAPACITY 128
#endif

#if CPLUS_HTTP_SERVER_MAX_CONNECTIONS < 1
#error CPLUS_HTTP_SERVER_MAX_CONNECTIONS must be at least 1
#endif
#if CPLUS_HTTP_SERVER_MAX_CONNECTIONS + 2 > CPLUS_THREAD_POOL_MAX_TASKS
#error CPLUS_HTTP_SERVER_MAX_CONNECTIONS plus the head and router tasks must fit the thread pool
#endif

#if defined(_WIN32)
typedef SRWLOCK http_server_mutex_t;
#else
#include <pthread.h>
typedef pthread_mutex_t http_server_mutex_t;
#endif

enum {
    HTTP_SERVER_OK = 0,
    HTTP_SERVER_INVALID_ARGUMENT = 1,
    HTTP_SERVER_INVALID_STATE = 2,
    HTTP_SERVER_FULL = 3,
    HTTP_SERVER_SOCKET_ERROR = 4,
    HTTP_SERVER_POOL_ERROR = 5,
    HTTP_SERVER_RESPONSE_TOO_LARGE = 6
};

struct http_server_t;
struct http_request_response_t;
typedef thread_step_result_t (*http_route_step_fn_t)(thread_task_t* task, struct http_request_response_t* exchange, void* route_context);

typedef struct http_request_response_t {
    unsigned long long unique_id;
    char request[CPLUS_HTTP_SERVER_REQUEST_CAPACITY];
    size_t request_length;
    int request_finished;
    char response[CPLUS_HTTP_SERVER_RESPONSE_CAPACITY];
    size_t response_length;
    int response_ready;
    int response_sent;
    net_socket_handle_t fd_connection;

    int in_use;
    int route_dispatched;
    int head_notified;
    int handler_submitted;
    size_t response_offset;
    size_t request_headers_end;
    struct http_server_t* owner;
    http_route_step_fn_t route_step;
    void* route_context;
    thread_task_t handler_task;
    unsigned char handler_state[CPLUS_HTTP_HANDLER_CONTEXT_BYTES];
} http_request_response_t;

typedef struct http_route_t {
    char method[CPLUS_HTTP_ROUTE_METHOD_CAPACITY];
    char path[CPLUS_HTTP_ROUTE_PATH_CAPACITY];
    http_route_step_fn_t step;
    void* context;
} http_route_t;

static thread_step_result_t http_server_head_step(thread_task_t* task, void* context);
static thread_step_result_t http_server_router_step(thread_task_t* task, void* context);
static thread_step_result_t http_server_dispatch_step(thread_task_t* task, void* context);

typedef struct http_server_t {
    http_server_mutex_t mutex;
    thread_pool_t* pool;
    net_socket_handle_t listen_fd;
    unsigned short bound_port;
    unsigned long long next_unique_id;
    http_route_t routes[CPLUS_HTTP_SERVER_MAX_ROUTES];
    size_t route_count;
    http_request_response_t exchanges[CPLUS_HTTP_SERVER_MAX_CONNECTIONS];
    thread_task_t head_task;
    thread_task_t router_task;
    int initialized;
    int started;
    int running;
    int winsock_started;
    int last_native_error;

    static priv int mutex_init(http_server_mutex_t* mutex) {
#if defined(_WIN32)
        InitializeSRWLock(mutex);
        return 1;
#else
        return pthread_mutex_init(mutex, NULL) == 0;
#endif
    }

    static priv int mutex_destroy(http_server_mutex_t* mutex) {
#if defined(_WIN32)
        (void)mutex;
        return 1;
#else
        return pthread_mutex_destroy(mutex) == 0;
#endif
    }

    static priv int mutex_lock(http_server_mutex_t* mutex) {
#if defined(_WIN32)
        AcquireSRWLockExclusive(mutex);
        return 1;
#else
        return pthread_mutex_lock(mutex) == 0;
#endif
    }

    static priv int mutex_unlock(http_server_mutex_t* mutex) {
#if defined(_WIN32)
        ReleaseSRWLockExclusive(mutex);
        return 1;
#else
        return pthread_mutex_unlock(mutex) == 0;
#endif
    }

    static priv void release_exchange_locked(http_request_response_t* exchange) {
        if (exchange == NULL) return;
        if (exchange->fd_connection != NET_INVALID_SOCKET) net_socket_t.close(exchange->fd_connection);
        exchange->fd_connection = NET_INVALID_SOCKET;
        exchange->in_use = 0;
        exchange->request_finished = 0;
        exchange->response_ready = 0;
        exchange->response_sent = 0;
        exchange->head_notified = 0;
    }

    static priv void set_simple_response_locked(
        http_request_response_t* exchange,
        int status_code,
        borrowed const char* reason,
        borrowed const char* body
    ) {
        if (exchange == NULL || reason == NULL || body == NULL) return;
        size_t body_length = strlen(body);
        int header_length = snprintf(
            exchange->response,
            sizeof(exchange->response),
            "HTTP/1.1 %d %s\r\nContent-Length: %lu\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n",
            status_code,
            reason,
            (unsigned long)body_length
        );
        if (header_length < 0 || (size_t)header_length + body_length >= sizeof(exchange->response)) {
            exchange->response_length = 0;
            exchange->response_ready = 0;
            return;
        }
        memcpy(exchange->response + header_length, body, body_length);
        exchange->response_length = (size_t)header_length + body_length;
        exchange->response[exchange->response_length] = '\0';
        exchange->response_ready = 1;
        exchange->head_notified = 1;
    }

    static priv int find_free_exchange_locked(http_server_t* self) {
        for (size_t index = 0; index < CPLUS_HTTP_SERVER_MAX_CONNECTIONS; index++) {
            if (!self->exchanges[index].in_use) return (int)index;
        }
        return -1;
    }

    static priv void accept_one_locked(http_server_t* self) {
        int native_error = 0;
        net_socket_handle_t accepted = net_socket_t.accept_one(self->listen_fd, &native_error);
        if (accepted == NET_INVALID_SOCKET) {
            if (!net_socket_t.is_would_block(native_error)) self->last_native_error = native_error;
            return;
        }
        int slot = http_server_t.find_free_exchange_locked(self);
        if (slot < 0) {
            net_socket_t.close(accepted);
            return;
        }
        http_request_response_t* exchange = &self->exchanges[slot];
        memset(exchange, 0, sizeof(*exchange));
        exchange->owner = self;
        exchange->fd_connection = accepted;
        exchange->in_use = 1;
        exchange->unique_id = ++self->next_unique_id;
        if (exchange->unique_id == 0) exchange->unique_id = ++self->next_unique_id;
    }

    static priv void receive_one_locked(http_server_t* self, http_request_response_t* exchange) {
        if (exchange->request_finished) return;
        size_t free_space = sizeof(exchange->request) - 1 - exchange->request_length;
        if (free_space == 0) {
            http_server_t.set_simple_response_locked(exchange, 413, "Payload Too Large", "request too large\n");
            exchange->request_finished = 1;
            exchange->route_dispatched = 1;
            return;
        }
        size_t chunk = free_space < CPLUS_HTTP_SERVER_IO_CHUNK ? free_space : CPLUS_HTTP_SERVER_IO_CHUNK;
        long count = net_socket_t.receive(exchange->fd_connection, exchange->request + exchange->request_length, chunk);
        if (count > 0) {
            exchange->request_length += (size_t)count;
            exchange->request[exchange->request_length] = '\0';
            size_t header_end = 0;
            int framing = http_protocol_t.request_complete(
                exchange->request,
                exchange->request_length,
                sizeof(exchange->request) - 1,
                &header_end
            );
            if (framing == HTTP_PROTOCOL_COMPLETE) {
                exchange->request_finished = 1;
                exchange->request_headers_end = header_end;
            } else if (framing == HTTP_PROTOCOL_TOO_LARGE) {
                http_server_t.set_simple_response_locked(exchange, 413, "Payload Too Large", "request too large\n");
                exchange->request_finished = 1;
                exchange->route_dispatched = 1;
            } else if (framing == HTTP_PROTOCOL_UNSUPPORTED) {
                http_server_t.set_simple_response_locked(exchange, 501, "Not Implemented", "transfer encoding unsupported\n");
                exchange->request_finished = 1;
                exchange->route_dispatched = 1;
            } else if (framing == HTTP_PROTOCOL_INVALID) {
                http_server_t.set_simple_response_locked(exchange, 400, "Bad Request", "malformed request\n");
                exchange->request_finished = 1;
                exchange->route_dispatched = 1;
            }
            return;
        }
        if (count == 0) {
            http_server_t.release_exchange_locked(exchange);
            return;
        }
        int native_error = net_socket_t.last_error();
        if (!net_socket_t.is_would_block(native_error)) {
            self->last_native_error = native_error;
            http_server_t.release_exchange_locked(exchange);
        }
    }

    static priv void send_one_locked(http_server_t* self, http_request_response_t* exchange) {
        if (!exchange->response_ready || !exchange->head_notified || exchange->response_sent) return;
        if (exchange->response_offset >= exchange->response_length) {
            exchange->response_sent = 1;
            http_server_t.release_exchange_locked(exchange);
            return;
        }
        size_t remaining = exchange->response_length - exchange->response_offset;
        size_t chunk = remaining < CPLUS_HTTP_SERVER_IO_CHUNK ? remaining : CPLUS_HTTP_SERVER_IO_CHUNK;
        long count = net_socket_t.send(exchange->fd_connection, exchange->response + exchange->response_offset, chunk);
        if (count > 0) {
            exchange->response_offset += (size_t)count;
            if (exchange->response_offset == exchange->response_length) {
                exchange->response_sent = 1;
                http_server_t.release_exchange_locked(exchange);
            }
            return;
        }
        if (count == 0) return;
        int native_error = net_socket_t.last_error();
        if (!net_socket_t.is_would_block(native_error)) {
            self->last_native_error = native_error;
            http_server_t.release_exchange_locked(exchange);
        }
    }

    static priv thread_step_result_t run_head_step(http_server_t* self) {
        if (self == NULL || !self->initialized) return thread_task_t.done();
        if (!http_server_t.mutex_lock(&self->mutex)) return thread_task_t.failed(HTTP_SERVER_SOCKET_ERROR);
        if (!self->running) {
            http_server_t.mutex_unlock(&self->mutex);
            return thread_task_t.done();
        }
        http_server_t.accept_one_locked(self);
        for (size_t index = 0; index < CPLUS_HTTP_SERVER_MAX_CONNECTIONS; index++) {
            http_request_response_t* exchange = &self->exchanges[index];
            if (!exchange->in_use) continue;
            http_server_t.receive_one_locked(self, exchange);
            if (exchange->in_use) http_server_t.send_one_locked(self, exchange);
        }
        http_server_t.mutex_unlock(&self->mutex);
        return thread_task_t.yield();
    }

    static priv int response_append_locked(
        http_request_response_t* exchange,
        borrowed const void* bytes,
        size_t length
    ) {
        if (exchange == NULL || (bytes == NULL && length != 0)) return HTTP_SERVER_INVALID_ARGUMENT;
        if (!exchange->in_use || exchange->response_ready || exchange->response_sent) return HTTP_SERVER_INVALID_STATE;
        if (length > sizeof(exchange->response) - 1 - exchange->response_length)
            return HTTP_SERVER_RESPONSE_TOO_LARGE;
        if (length > 0) memcpy(exchange->response + exchange->response_length, bytes, length);
        exchange->response_length += length;
        exchange->response[exchange->response_length] = '\0';
        return HTTP_SERVER_OK;
    }

    static priv int header_value_is_safe(borrowed const char* value) {
        if (value == NULL) return 0;
        for (size_t index = 0; value[index] != '\0'; index++) {
            unsigned char character = (unsigned char)value[index];
            if (character == '\r' || character == '\n' || (character < 0x20 && character != '\t')) return 0;
        }
        return 1;
    }

    static priv int dispatch_route_locked(http_server_t* self, http_request_response_t* exchange) {
        thread_pool_t* pool = self->pool;
        for (size_t route_index = 0; route_index < self->route_count; route_index++) {
            http_route_t* route = &self->routes[route_index];
            if (!http_protocol_t.route_matches(exchange->request, exchange->request_length, route->method, route->path)) continue;
            exchange->route_step = route->step;
            exchange->route_context = route->context;
            thread_task_t.init(&exchange->handler_task, http_server_dispatch_step, exchange);
            exchange->handler_submitted = 1;
            int submitted = pool->submit(&exchange->handler_task);
            if (submitted != THREAD_POOL_OK) {
                exchange->handler_submitted = 0;
                http_server_t.set_simple_response_locked(exchange, 503, "Service Unavailable", "server task capacity exhausted\n");
                exchange->head_notified = 1;
                return HTTP_SERVER_POOL_ERROR;
            }
            return HTTP_SERVER_OK;
        }
        http_server_t.set_simple_response_locked(exchange, 404, "Not Found", "not found\n");
        exchange->route_dispatched = 1;
        return HTTP_SERVER_OK;
    }

    static priv thread_step_result_t run_router_step(http_server_t* self) {
        if (self == NULL || !self->initialized) return thread_task_t.done();
        thread_pool_t* pool = self->pool;
        if (!http_server_t.mutex_lock(&self->mutex)) return thread_task_t.failed(HTTP_SERVER_SOCKET_ERROR);
        if (!self->running) {
            http_server_t.mutex_unlock(&self->mutex);
            return thread_task_t.done();
        }
        for (size_t index = 0; index < CPLUS_HTTP_SERVER_MAX_CONNECTIONS; index++) {
            http_request_response_t* exchange = &self->exchanges[index];
            if (!exchange->in_use) continue;
            if (exchange->request_finished && !exchange->route_dispatched) {
                exchange->route_dispatched = 1;
                http_server_t.dispatch_route_locked(self, exchange);
            }
            if (!exchange->handler_submitted || exchange->head_notified) continue;

            thread_task_state_t handler_state = THREAD_TASK_IDLE;
            int status_result = pool->status(&exchange->handler_task, &handler_state, NULL, NULL);
            if (status_result != THREAD_POOL_OK) {
                http_server_t.set_simple_response_locked(exchange, 500, "Internal Server Error", "handler status unavailable\n");
                exchange->head_notified = 1;
            } else if (handler_state == THREAD_TASK_COMPLETED && exchange->response_ready) {
                exchange->head_notified = 1;
            } else if (handler_state == THREAD_TASK_FAILED || handler_state == THREAD_TASK_CANCELLED) {
                http_server_t.set_simple_response_locked(exchange, 500, "Internal Server Error", "route handler failed\n");
                exchange->head_notified = 1;
            } else if (handler_state == THREAD_TASK_COMPLETED && !exchange->response_ready) {
                http_server_t.set_simple_response_locked(exchange, 500, "Internal Server Error", "route produced no response\n");
                exchange->head_notified = 1;
            }
        }
        http_server_t.mutex_unlock(&self->mutex);
        return thread_task_t.yield();
    }

    pub int init(borrowed mut *self, borrowed mut thread_pool_t* pool) {
        if (self == NULL || pool == NULL) return HTTP_SERVER_INVALID_ARGUMENT;
        memset(self, 0, sizeof(*self));
        if (!http_server_t.mutex_init(&self->mutex)) return HTTP_SERVER_SOCKET_ERROR;
        self->pool = pool;
        self->listen_fd = NET_INVALID_SOCKET;
        self->initialized = 1;
        for (size_t index = 0; index < CPLUS_HTTP_SERVER_MAX_CONNECTIONS; index++) {
            self->exchanges[index].owner = self;
            self->exchanges[index].fd_connection = NET_INVALID_SOCKET;
        }
        return HTTP_SERVER_OK;
    }

    pub int add_route(
        borrowed mut *self,
        borrowed const char* method,
        borrowed const char* path,
        http_route_step_fn_t step,
        void* route_context
    ) {
        if (self == NULL || method == NULL || path == NULL || step == NULL || method[0] == '\0' || path[0] != '/')
            return HTTP_SERVER_INVALID_ARGUMENT;
        if (!self->initialized || self->started) return HTTP_SERVER_INVALID_STATE;
        size_t method_length = strlen(method);
        size_t path_length = strlen(path);
        if (method_length >= CPLUS_HTTP_ROUTE_METHOD_CAPACITY || path_length >= CPLUS_HTTP_ROUTE_PATH_CAPACITY)
            return HTTP_SERVER_INVALID_ARGUMENT;
        if (self->route_count >= CPLUS_HTTP_SERVER_MAX_ROUTES) return HTTP_SERVER_FULL;
        for (size_t index = 0; index < self->route_count; index++) {
            if (strcmp(self->routes[index].method, method) == 0 && strcmp(self->routes[index].path, path) == 0)
                return HTTP_SERVER_INVALID_ARGUMENT;
        }
        http_route_t* route = &self->routes[self->route_count++];
        memcpy(route->method, method, method_length + 1);
        memcpy(route->path, path, path_length + 1);
        route->step = step;
        route->context = route_context;
        return HTTP_SERVER_OK;
    }

    pub int start(borrowed mut *self, unsigned short port) {
        if (self == NULL || !self->initialized) return HTTP_SERVER_INVALID_ARGUMENT;
        if (self->started) return HTTP_SERVER_INVALID_STATE;
        if (net_socket_t.startup() != NET_SOCKET_OK) {
            self->last_native_error = net_socket_t.last_error();
            return HTTP_SERVER_SOCKET_ERROR;
        }
        self->winsock_started = 1;
        int native_error = 0;
        if (net_socket_t.bind_listen_ipv4(port, 64, &self->listen_fd, &self->bound_port, &native_error) != NET_SOCKET_OK) {
            self->last_native_error = native_error;
            net_socket_t.cleanup();
            self->winsock_started = 0;
            return HTTP_SERVER_SOCKET_ERROR;
        }
        self->running = 1;
        self->started = 1;
        thread_pool_t* pool = self->pool;
        thread_task_t.init(&self->head_task, http_server_head_step, self);
        int head_result = pool->submit(&self->head_task);
        if (head_result != THREAD_POOL_OK) {
            self->running = 0;
            net_socket_t.close(self->listen_fd);
            self->listen_fd = NET_INVALID_SOCKET;
            net_socket_t.cleanup();
            self->winsock_started = 0;
            self->started = 0;
            return HTTP_SERVER_POOL_ERROR;
        }
        thread_task_t.init(&self->router_task, http_server_router_step, self);
        int router_result = pool->submit(&self->router_task);
        if (router_result != THREAD_POOL_OK) {
            if (http_server_t.mutex_lock(&self->mutex)) {
                self->running = 0;
                http_server_t.mutex_unlock(&self->mutex);
            }
            pool->cancel(&self->head_task);
            pool->wait(&self->head_task);
            net_socket_t.close(self->listen_fd);
            self->listen_fd = NET_INVALID_SOCKET;
            net_socket_t.cleanup();
            self->winsock_started = 0;
            self->started = 0;
            return HTTP_SERVER_POOL_ERROR;
        }
        return HTTP_SERVER_OK;
    }

    pub unsigned short port(borrowed *self) {
        return self == NULL ? 0 : self->bound_port;
    }

    pub int append_response(
        borrowed mut *self,
        borrowed mut http_request_response_t* exchange,
        borrowed const void* bytes,
        size_t length
    ) {
        if (self == NULL || exchange == NULL || exchange->owner != self || !self->initialized)
            return HTTP_SERVER_INVALID_ARGUMENT;
        if (!http_server_t.mutex_lock(&self->mutex)) return HTTP_SERVER_SOCKET_ERROR;
        int result = http_server_t.response_append_locked(exchange, bytes, length);
        http_server_t.mutex_unlock(&self->mutex);
        return result;
    }

    pub int finish_response(borrowed mut *self, borrowed mut http_request_response_t* exchange) {
        if (self == NULL || exchange == NULL || exchange->owner != self || !self->initialized)
            return HTTP_SERVER_INVALID_ARGUMENT;
        if (!http_server_t.mutex_lock(&self->mutex)) return HTTP_SERVER_SOCKET_ERROR;
        if (!exchange->in_use || exchange->response_ready || exchange->response_length == 0) {
            http_server_t.mutex_unlock(&self->mutex);
            return HTTP_SERVER_INVALID_STATE;
        }
        exchange->response_ready = 1;
        http_server_t.mutex_unlock(&self->mutex);
        return HTTP_SERVER_OK;
    }

    pub int respond(
        borrowed mut *self,
        borrowed mut http_request_response_t* exchange,
        int status_code,
        borrowed const char* reason,
        borrowed const char* content_type,
        borrowed const void* body,
        size_t body_length
    ) {
        if (self == NULL || exchange == NULL || reason == NULL || content_type == NULL ||
            (body == NULL && body_length > 0) || exchange->owner != self || !self->initialized)
            return HTTP_SERVER_INVALID_ARGUMENT;
        if (status_code < 100 || status_code > 599 || !http_server_t.header_value_is_safe(reason) ||
            !http_server_t.header_value_is_safe(content_type)) return HTTP_SERVER_INVALID_ARGUMENT;
        if (!http_server_t.mutex_lock(&self->mutex)) return HTTP_SERVER_SOCKET_ERROR;
        if (!exchange->in_use || exchange->response_ready || exchange->response_sent) {
            http_server_t.mutex_unlock(&self->mutex);
            return HTTP_SERVER_INVALID_STATE;
        }
        int header_length = snprintf(
            exchange->response,
            sizeof(exchange->response),
            "HTTP/1.1 %d %s\r\nContent-Length: %lu\r\nContent-Type: %s\r\nConnection: close\r\n\r\n",
            status_code,
            reason,
            (unsigned long)body_length,
            content_type
        );
        if (header_length < 0 || (size_t)header_length + body_length >= sizeof(exchange->response)) {
            http_server_t.mutex_unlock(&self->mutex);
            return HTTP_SERVER_RESPONSE_TOO_LARGE;
        }
        if (body_length > 0) memcpy(exchange->response + header_length, body, body_length);
        exchange->response_length = (size_t)header_length + body_length;
        exchange->response[exchange->response_length] = '\0';
        exchange->response_ready = 1;
        http_server_t.mutex_unlock(&self->mutex);
        return HTTP_SERVER_OK;
    }

    pub int stop(borrowed mut *self) {
        if (self == NULL || !self->initialized) return HTTP_SERVER_INVALID_ARGUMENT;
        if (!self->started) return HTTP_SERVER_OK;
        if (http_server_t.mutex_lock(&self->mutex)) {
            self->running = 0;
            http_server_t.mutex_unlock(&self->mutex);
        }
        thread_pool_t* pool = self->pool;
        pool->cancel(&self->head_task);
        pool->cancel(&self->router_task);
        pool->wait(&self->head_task);
        pool->wait(&self->router_task);

        for (size_t index = 0; index < CPLUS_HTTP_SERVER_MAX_CONNECTIONS; index++) {
            http_request_response_t* exchange = &self->exchanges[index];
            if (exchange->handler_submitted) {
                pool->cancel(&exchange->handler_task);
                pool->wait(&exchange->handler_task);
            }
        }
        if (http_server_t.mutex_lock(&self->mutex)) {
            for (size_t index = 0; index < CPLUS_HTTP_SERVER_MAX_CONNECTIONS; index++)
                http_server_t.release_exchange_locked(&self->exchanges[index]);
            if (self->listen_fd != NET_INVALID_SOCKET) net_socket_t.close(self->listen_fd);
            self->listen_fd = NET_INVALID_SOCKET;
            http_server_t.mutex_unlock(&self->mutex);
        }
        if (self->winsock_started) {
            net_socket_t.cleanup();
            self->winsock_started = 0;
        }
        self->started = 0;
        return HTTP_SERVER_OK;
    }

    pub int destroy(borrowed mut *self) {
        if (self == NULL) return HTTP_SERVER_INVALID_ARGUMENT;
        if (!self->initialized) return HTTP_SERVER_OK;
        int result = self->stop();
        if (result != HTTP_SERVER_OK) return result;
        http_server_t.mutex_destroy(&self->mutex);
        memset(self, 0, sizeof(*self));
        self->listen_fd = NET_INVALID_SOCKET;
        return HTTP_SERVER_OK;
    }
} http_server_t;

static thread_step_result_t http_server_head_step(thread_task_t* task, void* context) {
    (void)task;
    return http_server_t.run_head_step((http_server_t*)context);
}

static thread_step_result_t http_server_router_step(thread_task_t* task, void* context) {
    (void)task;
    return http_server_t.run_router_step((http_server_t*)context);
}

static thread_step_result_t http_server_dispatch_step(thread_task_t* task, void* context) {
    http_request_response_t* exchange = (http_request_response_t*)context;
    if (exchange == NULL || exchange->route_step == NULL) return thread_task_t.failed(HTTP_SERVER_INVALID_STATE);
    http_route_step_fn_t route_step = exchange->route_step;
    void* route_context = exchange->route_context;
    return route_step(task, exchange, route_context);
}

#endif
