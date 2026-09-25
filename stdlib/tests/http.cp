comptime import "stdlib:/http/client.cp";
comptime import "stdlib:/http/server.cp";

typedef struct http_test_route_context_t {
    size_t expected_request_length;
    int callback_steps;
} http_test_route_context_t;

thread_step_result_t http_test_upload_route(
    thread_task_t* task,
    http_request_response_t* exchange,
    void* raw_context
) {
    (void)task;
    http_test_route_context_t* context = (http_test_route_context_t*)raw_context;
    context->callback_steps++;

    if (exchange->handler_state[0] == 0) {
        exchange->handler_state[0] = 1;
        return thread_task_t.yield();
    }

    if (exchange->request_length != context->expected_request_length)
        return thread_task_t.failed(HTTP_SERVER_INVALID_STATE);

    http_server_t* server = exchange->owner;
    int status = server->respond(exchange, 200, "OK", "text/plain", "received", 8);
    return status == HTTP_SERVER_OK ? thread_task_t.done() : thread_task_t.failed(status);
}

@test "HTTP protocol validates framing and route matching" {
    char complete[] = "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\ndata";
    char partial[128] = "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\nda";
    char malformed[] = "GET / HTTP/1.0\r\n\r\n";
    char chunked[] = "POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n";
    char oversized[] = "POST /upload HTTP/1.1\r\nContent-Length: 10\r\n\r\n";
    char conflicting_lengths[] = "POST /upload HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx";
    char fixed_response[] = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello";
    char close_response[] = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n";
    char chunked_response[] = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
    size_t header_end = 0;
    size_t response_total = 0;
    int close_delimited = 0;

    @assertEquals(HTTP_PROTOCOL_COMPLETE, http_protocol_t.request_complete(complete, strlen(complete), sizeof(complete) - 1, &header_end));
    @assertEquals(HTTP_PROTOCOL_NEED_MORE, http_protocol_t.request_complete(partial, strlen(partial), sizeof(partial) - 1, &header_end));
    @assertEquals(HTTP_PROTOCOL_INVALID, http_protocol_t.request_complete(malformed, strlen(malformed), sizeof(malformed) - 1, &header_end));
    @assertEquals(HTTP_PROTOCOL_UNSUPPORTED, http_protocol_t.request_complete(chunked, strlen(chunked), sizeof(chunked) - 1, &header_end));
    @assertEquals(HTTP_PROTOCOL_TOO_LARGE, http_protocol_t.request_complete(oversized, strlen(oversized), sizeof(oversized) - 1, &header_end));
    @assertEquals(HTTP_PROTOCOL_INVALID, http_protocol_t.request_complete(conflicting_lengths, strlen(conflicting_lengths), sizeof(conflicting_lengths) - 1, &header_end));
    @assert(http_protocol_t.route_matches(complete, strlen(complete), "POST", "/upload"));
    @assert(!http_protocol_t.route_matches(complete, strlen(complete), "GET", "/upload"));
    @assertEquals(HTTP_PROTOCOL_COMPLETE, http_protocol_t.response_framing(fixed_response, strlen(fixed_response), &header_end, &response_total, &close_delimited));
    @assertEquals(strlen(fixed_response), response_total);
    @assertEquals(0, close_delimited);
    @assertEquals(HTTP_PROTOCOL_COMPLETE, http_protocol_t.response_framing(close_response, strlen(close_response), &header_end, &response_total, &close_delimited));
    @assertEquals(1, close_delimited);
    @assertEquals(HTTP_PROTOCOL_UNSUPPORTED, http_protocol_t.response_framing(chunked_response, strlen(chunked_response), &header_end, &response_total, &close_delimited));
}

@test "HTTP client reports connection refusal" {
    char response[256];
    size_t response_length = 0;
    int native_error = 0;
    int status = http_client_t.request(
        "127.0.0.1",
        0,
        "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
        1000,
        response,
        sizeof(response),
        &response_length,
        &native_error
    );

    @assertEquals(HTTP_CLIENT_CONNECT_ERROR, status);
    @assert(native_error != 0);
    @assertEquals((size_t)0, response_length);
}

@test "HTTP server routes fragmented requests and serves route misses" {
    thread_pool_t pool = {0};
    http_server_t server = {0};
    http_test_route_context_t route_context = {0, 0};
    char request[4096] = {0};
    char response[4096] = {0};
    size_t response_length = 0;
    int native_error = 0;

    defer {
        server.destroy();
        pool.destroy();
    }

    @assertEquals(THREAD_POOL_OK, pool.init(1));
    @assertEquals(HTTP_SERVER_OK, server.init(&pool));
    @assertEquals(HTTP_SERVER_OK, server.add_route("POST", "/upload", http_test_upload_route, &route_context));
    @assertEquals(HTTP_SERVER_OK, server.start(0));
    @assert(server.port() != 0);

    int header_length = snprintf(
        request,
        sizeof(request),
        "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2048\r\nConnection: close\r\n\r\n"
    );
    @assert(header_length > 0);
    memset(request + header_length, 'x', 2048);
    request[header_length + 2048] = '\0';
    route_context.expected_request_length = (size_t)header_length + 2048;

    int status = http_client_t.request(
        "127.0.0.1",
        server.port(),
        request,
        3000,
        response,
        sizeof(response),
        &response_length,
        &native_error
    );
    @assertEquals(HTTP_CLIENT_OK, status);
    @assert(strstr(response, "HTTP/1.1 200 OK") != NULL);
    @assert(strstr(response, "received") != NULL);
    @assertEquals(2, route_context.callback_steps);

    status = http_client_t.request(
        "127.0.0.1",
        server.port(),
        "GET /missing HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
        3000,
        response,
        sizeof(response),
        &response_length,
        &native_error
    );
    @assertEquals(HTTP_CLIENT_OK, status);
    @assert(strstr(response, "HTTP/1.1 404 Not Found") != NULL);

    char tiny_response[16];
    status = http_client_t.request(
        "127.0.0.1",
        server.port(),
        "GET /missing HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
        3000,
        tiny_response,
        sizeof(tiny_response),
        &response_length,
        &native_error
    );
    @assertEquals(HTTP_CLIENT_RESPONSE_TOO_LARGE, status);

    status = http_client_t.request(
        "127.0.0.1",
        server.port(),
        "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\nConnection: close\r\n\r\nshort",
        100,
        response,
        sizeof(response),
        &response_length,
        &native_error
    );
    @assertEquals(HTTP_CLIENT_TIMEOUT, status);
}

@test "HTTP server progresses with two pool workers" {
    thread_pool_t pool = {0};
    http_server_t server = {0};
    http_test_route_context_t route_context = {0, 0};
    char request[] = "POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    char response[512] = {0};
    size_t response_length = 0;
    int native_error = 0;

    defer {
        server.destroy();
        pool.destroy();
    }

    route_context.expected_request_length = strlen(request);
    @assertEquals(THREAD_POOL_OK, pool.init(2));
    @assertEquals(HTTP_SERVER_OK, server.init(&pool));
    @assertEquals(HTTP_SERVER_OK, server.add_route("POST", "/upload", http_test_upload_route, &route_context));
    @assertEquals(HTTP_SERVER_OK, server.start(0));
    int status = http_client_t.request(
        "127.0.0.1",
        server.port(),
        request,
        3000,
        response,
        sizeof(response),
        &response_length,
        &native_error
    );
    @assertEquals(HTTP_CLIENT_OK, status);
    @assert(strstr(response, "HTTP/1.1 200 OK") != NULL);
    @assertEquals(2, route_context.callback_steps);
}
