/*
 * Start with: cpc run stdlib/examples/http_server.cp
 * In another terminal: cpc run stdlib/examples/http_client.cp
 * Press Enter here to stop the server cleanly.
 */
comptime import "stdlib:/http/server.cp";

#include <stdio.h>
#include <string.h>

#define HTTP_EXAMPLE_PORT 18081

thread_step_result_t hello_route(
    thread_task_t* task,
    http_request_response_t* exchange,
    void* route_context
) {
    (void)task;
    (void)route_context;

    /* Demonstrate resumable work: return control once before responding. */
    if (exchange->handler_state[0]++ == 0) return thread_task_t.yield();

    const char* body = "Hello from the C-plus HTTP server!\n";
    http_server_t* server = exchange->owner;
    int result = server->respond(
        exchange,
        200,
        "OK",
        "text/plain; charset=utf-8",
        body,
        strlen(body)
    );
    return result == HTTP_SERVER_OK
        ? thread_task_t.done()
        : thread_task_t.failed(result);
}

int main(void) {
    thread_pool_t pool = {0};
    http_server_t server = {0};

    if (pool.init(2) != THREAD_POOL_OK) {
        fprintf(stderr, "could not start the server thread pool\n");
        return 1;
    }
    if (server.init(&pool) != HTTP_SERVER_OK) {
        fprintf(stderr, "could not initialize the HTTP server\n");
        pool.destroy();
        return 1;
    }
    if (server.add_route("GET", "/hello", hello_route, NULL) != HTTP_SERVER_OK) {
        fprintf(stderr, "could not register the /hello route\n");
        server.destroy();
        pool.destroy();
        return 1;
    }
    if (server.start(HTTP_EXAMPLE_PORT) != HTTP_SERVER_OK) {
        fprintf(stderr, "could not start the HTTP listener (native error %d)\n",
                server.last_native_error);
        server.destroy();
        pool.destroy();
        return 1;
    }

    printf("HTTP server listening at http://127.0.0.1:%u/hello\n",
           (unsigned int)server.port());
    printf("Press Enter to stop.\n");
    fflush(stdout);
    getchar();

    server.stop();
    server.destroy();
    pool.destroy();
    return 0;
}
