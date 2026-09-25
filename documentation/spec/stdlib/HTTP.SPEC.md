# HTTP Client and Server Specification

Status: initial HTTP/1.1 subset implemented by `stdlib:/http/`.

This is a deliberately small TCP HTTP layer, not a general web framework. Update this document and `stdlib/tests/http.cp` when request framing, route dispatch, limits, or socket behavior changes.

## Shared protocol limits

The protocol helper recognizes `\r\n\r\n`, case-insensitive `Content-Length`, and valid HTTP/1.1 request lines. Duplicate or malformed content lengths are rejected. Chunked transfer encoding, TLS, upgrades, pipelining, keep-alive reuse, and multipart parsing are unsupported. The server handles one request per connection and always closes after the response. A request without `Content-Length` is complete at the end of its headers. The client accepts a response with `Content-Length`, or reads a close-delimited response until EOF; chunked responses are reported as unsupported.

## Raw HTTP client

`stdlib:/http/client.cp` exposes `http_client_t.request(host, port, raw_request, timeout_ms, response, response_capacity, &response_length, &native_error)`. It sends the request bytes exactly as supplied (without the terminating NUL), waits for the response, NUL-terminates the output when capacity permits, and reports the byte length separately. A positive timeout is one total deadline for connect, send, and response receive. DNS resolution uses the system resolver and may block outside that deadline. The client tries resolved addresses in order, uses a nonblocking socket and readiness waits, handles partial sends/receives, and closes every socket on success or error.

Errors distinguish invalid arguments, DNS resolution, connection failure, timeout, send failure, receive failure, response framing, and output-capacity overflow. The native socket error is available through the output argument. Responses exceeding the caller's buffer fail instead of being truncated. A close-delimited response needs the peer to close; callers requesting a persistent connection must provide framing such as `Content-Length`.

## Cooperative server

`stdlib:/http/server.cp` accepts an already initialized `thread_pool_t`; the caller owns and shuts down the pool. `http_server_t.start(port)` opens a nonblocking IPv4 listener (port zero requests an OS-assigned port) and submits persistent head and router tasks. The server keeps a fixed-capacity array of stable `http_request_response_t` exchange slots. Each slot contains a unique ID, raw request and response buffers, request/response flags, connection handle, route/handler progress, and partial-send offset. Capacity and request/response/chunk limits are compile-time macros.

The head callback performs bounded work then yields: at most one `accept` attempt per activation; at most one receive chunk per live connection per activation; and at most one send chunk per response-ready connection per activation. Request bytes are retained until headers and any declared content-length body have arrived. Oversized, malformed, and unsupported chunked requests receive a small error response or are closed as appropriate. Only one request is served on each connection.

The router is a separate persistent pool task. Routes are registered before `start` as exact method/path pairs. After a request is complete, the router finds its route and submits that route's resumable step callback to the same pool with the exchange as its per-request context. A handler can keep its continuation in the exchange's reserved user-state bytes, do bounded work, append response bytes, and return `thread_task_t.yield()` until finished. It publishes completion through `http_server_t.finish_response(exchange)` and then returns `done`. The router observes the completed response and sets the head-notification flag; the head then sends it incrementally. Missing routes produce 404; pool saturation or handler failure produces 503/500.

All shared exchange state is protected by the server mutex. Do not retain an exchange pointer after the response has been sent and the slot released. Do not perform blocking work in route callbacks: split computation, file reads, or downstream operations into resumable bounded steps. Every persistent server callback yields each activation. The pool may use one or two native workers; callbacks and route-specific user state must remain safe under that concurrency.

Example resumable route:

```c
thread_step_result_t health_route(
    thread_task_t* task, http_request_response_t* exchange, void* context
) {
    (void)task;
    (void)context;
    if (exchange->handler_state[0]++ == 0) return thread_task_t.yield();
    http_server_t* server = exchange->owner;
    int result = server->respond(exchange, 200, "OK", "text/plain", "ok", 2);
    return result == HTTP_SERVER_OK ? thread_task_t.done() : thread_task_t.failed(result);
}

thread_pool_t pool = {0};
http_server_t server = {0};
pool.init(1);
server.init(&pool);
server.add_route("GET", "/health", health_route, NULL);
server.start(8080);
/* later, outside a pool callback: */
server.stop();
server.destroy();
pool.destroy();
```

Handlers own their continuation data. They must return `done` immediately after publishing a final response; they must not touch the exchange after completion.

## Platform and limits

The socket facade uses POSIX nonblocking sockets and `select`, or WinSock nonblocking sockets and `select`. Windows builds link `ws2_32`; Linux links pthreads through the thread-pool module. Server bind/listen is IPv4-only; the client resolves IPv4 and IPv6 addresses. The server is intentionally bounded and does not allocate memory per request. Define the `CPLUS_HTTP_*` capacity macros before importing the modules to change defaults.

## Validation

`stdlib/tests/http.cp` exercises HTTP/1.1 request/response framing, unsupported transfer encoding, connection-refusal and timeout reporting, response-buffer overflow, a loopback raw request with a multi-chunk body, route misses, and a handler that yields/resumes with both one- and two-worker pools. Run it with `cpc test stdlib/tests/http.cp`.
