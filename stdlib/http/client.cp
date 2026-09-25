#ifndef CPLUS_STDLIB_HTTP_CLIENT_CP
#define CPLUS_STDLIB_HTTP_CLIENT_CP

#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>

comptime import "stdlib:/net/socket.cp";
comptime import "stdlib:/http/protocol.cp";

#ifndef CPLUS_HTTP_CLIENT_IO_CHUNK
#define CPLUS_HTTP_CLIENT_IO_CHUNK 1024
#endif

enum {
    HTTP_CLIENT_OK = 0,
    HTTP_CLIENT_INVALID_ARGUMENT = 1,
    HTTP_CLIENT_DNS_ERROR = 2,
    HTTP_CLIENT_CONNECT_ERROR = 3,
    HTTP_CLIENT_TIMEOUT = 4,
    HTTP_CLIENT_SEND_ERROR = 5,
    HTTP_CLIENT_RECEIVE_ERROR = 6,
    HTTP_CLIENT_PROTOCOL_ERROR = 7,
    HTTP_CLIENT_RESPONSE_TOO_LARGE = 8,
    HTTP_CLIENT_UNSUPPORTED_FRAMING = 9
};

typedef struct http_client_t {
    static priv int wait_until(net_socket_handle_t fd, int writable, unsigned long long deadline) {
        for (;;) {
            unsigned long long now = net_socket_t.now_ms();
            if (now >= deadline) return NET_SOCKET_TIMEOUT;
            unsigned long long remaining = deadline - now;
            unsigned int timeout = remaining > (unsigned long long)UINT_MAX
                ? UINT_MAX
                : (unsigned int)remaining;
            int result = net_socket_t.wait(fd, writable, timeout);
            if (result != NET_SOCKET_ERROR) return result;
#if !defined(_WIN32)
            int error_code = net_socket_t.last_error();
            if (error_code == EINTR) continue;
#endif
            return NET_SOCKET_ERROR;
        }
    }

    static pub int request(
        borrowed const char* host,
        unsigned short port,
        borrowed const char* raw_request,
        unsigned int timeout_ms,
        borrowed mut char* response,
        size_t response_capacity,
        borrowed mut size_t* response_length,
        borrowed mut int* native_error
    ) {
        int result = HTTP_CLIENT_INVALID_ARGUMENT;
        int startup_result = 0;
        int resolved = 0;
        size_t request_length = 0;
        size_t sent = 0;
        size_t received = 0;
        size_t response_total = 0;
        size_t response_header_end = 0;
        net_socket_handle_t fd = NET_INVALID_SOCKET;
        struct addrinfo hints;
        struct addrinfo* addresses = NULL;
        struct addrinfo* candidate = NULL;
        char service[8];
        unsigned long long deadline = 0;

        if (response_length != NULL) *response_length = 0;
        if (native_error != NULL) *native_error = 0;
        if (response != NULL && response_capacity > 0) response[0] = '\0';
        if (host == NULL || host[0] == '\0' || raw_request == NULL || raw_request[0] == '\0' ||
            timeout_ms == 0 || response == NULL || response_capacity < 2 || response_length == NULL) {
            return HTTP_CLIENT_INVALID_ARGUMENT;
        }

        request_length = strlen(raw_request);
        deadline = net_socket_t.now_ms() + (unsigned long long)timeout_ms;
        startup_result = net_socket_t.startup();
        if (startup_result != NET_SOCKET_OK) {
            if (native_error != NULL) *native_error = net_socket_t.last_error();
            return HTTP_CLIENT_CONNECT_ERROR;
        }

        memset(&hints, 0, sizeof(hints));
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        hints.ai_protocol = IPPROTO_TCP;
        snprintf(service, sizeof(service), "%u", (unsigned int)port);
        int address_result = getaddrinfo(host, service, &hints, &addresses);
        if (address_result != 0) {
            if (native_error != NULL) *native_error = address_result;
            result = HTTP_CLIENT_DNS_ERROR;
            goto cleanup;
        }
        resolved = 1;
        if (net_socket_t.now_ms() >= deadline) {
            result = HTTP_CLIENT_TIMEOUT;
            goto cleanup;
        }

        result = HTTP_CLIENT_CONNECT_ERROR;
        for (candidate = addresses; candidate != NULL; candidate = candidate->ai_next) {
            if (net_socket_t.now_ms() >= deadline) {
                result = HTTP_CLIENT_TIMEOUT;
                goto cleanup;
            }
            fd = net_socket_t.create_tcp(candidate->ai_family);
            if (fd == NET_INVALID_SOCKET) {
                if (native_error != NULL) *native_error = net_socket_t.last_error();
                continue;
            }
            if (net_socket_t.set_nonblocking(fd) != NET_SOCKET_OK) {
                if (native_error != NULL) *native_error = net_socket_t.last_error();
                net_socket_t.close(fd);
                fd = NET_INVALID_SOCKET;
                continue;
            }
#if defined(_WIN32)
            int address_length = (int)candidate->ai_addrlen;
#else
            socklen_t address_length = (socklen_t)candidate->ai_addrlen;
#endif
            if (connect(fd, candidate->ai_addr, address_length) == 0) {
                result = HTTP_CLIENT_OK;
                break;
            }
            int connect_error = net_socket_t.last_error();
            if (net_socket_t.is_connect_pending(connect_error)) {
                int ready = http_client_t.wait_until(fd, 1, deadline);
                if (ready == NET_SOCKET_TIMEOUT) {
                    result = HTTP_CLIENT_TIMEOUT;
                    goto cleanup;
                }
                if (ready == NET_SOCKET_READY) {
                    int socket_error = 0;
                    if (net_socket_t.socket_error(fd, &socket_error) == NET_SOCKET_OK && socket_error == 0) {
                        result = HTTP_CLIENT_OK;
                        break;
                    }
                    if (native_error != NULL) *native_error = socket_error != 0 ? socket_error : net_socket_t.last_error();
                } else if (native_error != NULL) {
                    *native_error = net_socket_t.last_error();
                }
            } else if (native_error != NULL) {
                *native_error = connect_error;
            }
            net_socket_t.close(fd);
            fd = NET_INVALID_SOCKET;
        }
        if (result != HTTP_CLIENT_OK) goto cleanup;

        while (sent < request_length) {
            if (net_socket_t.now_ms() >= deadline) {
                result = HTTP_CLIENT_TIMEOUT;
                goto cleanup;
            }
            long count = net_socket_t.send(fd, raw_request + sent, request_length - sent);
            if (count > 0) {
                sent += (size_t)count;
                continue;
            }
            int error_code = net_socket_t.last_error();
            if (count < 0 && net_socket_t.is_would_block(error_code)) {
                int ready = http_client_t.wait_until(fd, 1, deadline);
                if (ready == NET_SOCKET_TIMEOUT) {
                    result = HTTP_CLIENT_TIMEOUT;
                    goto cleanup;
                }
                if (ready == NET_SOCKET_READY) continue;
                if (native_error != NULL) *native_error = net_socket_t.last_error();
            } else if (count < 0) {
                if (native_error != NULL) *native_error = error_code;
            }
            result = HTTP_CLIENT_SEND_ERROR;
            goto cleanup;
        }

        result = HTTP_CLIENT_RECEIVE_ERROR;
        for (;;) {
            if (net_socket_t.now_ms() >= deadline) {
                result = HTTP_CLIENT_TIMEOUT;
                goto cleanup;
            }
            size_t remaining_capacity = response_capacity - 1 - received;
            if (remaining_capacity == 0) {
                result = HTTP_CLIENT_RESPONSE_TOO_LARGE;
                goto cleanup;
            }
            size_t chunk = remaining_capacity < CPLUS_HTTP_CLIENT_IO_CHUNK
                ? remaining_capacity
                : CPLUS_HTTP_CLIENT_IO_CHUNK;
            long count = net_socket_t.receive(fd, response + received, chunk);
            if (count > 0) {
                received += (size_t)count;
                response[received] = '\0';
                int close_delimited = 0;
                int framing = http_protocol_t.response_framing(
                    response,
                    received,
                    &response_header_end,
                    &response_total,
                    &close_delimited
                );
                if (framing == HTTP_PROTOCOL_UNSUPPORTED) {
                    result = HTTP_CLIENT_UNSUPPORTED_FRAMING;
                    goto cleanup;
                }
                if (framing == HTTP_PROTOCOL_INVALID || framing == HTTP_PROTOCOL_TOO_LARGE) {
                    result = HTTP_CLIENT_PROTOCOL_ERROR;
                    goto cleanup;
                }
                if (framing == HTTP_PROTOCOL_COMPLETE && !close_delimited) {
                    if (response_total > response_capacity - 1) {
                        result = HTTP_CLIENT_RESPONSE_TOO_LARGE;
                        goto cleanup;
                    }
                    if (received >= response_total) {
                        result = HTTP_CLIENT_OK;
                        goto cleanup;
                    }
                }
                continue;
            }
            if (count == 0) {
                if (received == 0) {
                    result = HTTP_CLIENT_PROTOCOL_ERROR;
                    goto cleanup;
                }
                int close_delimited = 0;
                int framing = http_protocol_t.response_framing(
                    response,
                    received,
                    &response_header_end,
                    &response_total,
                    &close_delimited
                );
                if (framing == HTTP_PROTOCOL_COMPLETE && close_delimited) {
                    result = HTTP_CLIENT_OK;
                    goto cleanup;
                }
                if (framing == HTTP_PROTOCOL_COMPLETE && received >= response_total) {
                    result = HTTP_CLIENT_OK;
                    goto cleanup;
                }
                result = HTTP_CLIENT_PROTOCOL_ERROR;
                goto cleanup;
            }

            int error_code = net_socket_t.last_error();
            if (net_socket_t.is_would_block(error_code)) {
                int ready = http_client_t.wait_until(fd, 0, deadline);
                if (ready == NET_SOCKET_TIMEOUT) {
                    result = HTTP_CLIENT_TIMEOUT;
                    goto cleanup;
                }
                if (ready == NET_SOCKET_READY) continue;
                if (native_error != NULL) *native_error = net_socket_t.last_error();
            } else if (native_error != NULL) {
                *native_error = error_code;
            }
            result = HTTP_CLIENT_RECEIVE_ERROR;
            goto cleanup;
        }

cleanup:
        if (fd != NET_INVALID_SOCKET) net_socket_t.close(fd);
        if (resolved) freeaddrinfo(addresses);
        if (startup_result == NET_SOCKET_OK) net_socket_t.cleanup();
        if (response != NULL && response_capacity > 0) response[received] = '\0';
        if (response_length != NULL) *response_length = received;
        return result;
    }
} http_client_t;

#endif
