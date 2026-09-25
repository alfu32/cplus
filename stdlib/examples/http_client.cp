/*
 * Start http_server.cp in another terminal, then run:
 *   cpc run stdlib/examples/http_client.cp
 */
comptime import "stdlib:/http/client.cp";

#include <stdio.h>

int main(void) {
    char response[8192];
    size_t response_length = 0;
    int native_error = 0;
    const char* request =
        "GET /hello HTTP/1.1\r\n"
        "Host: localhost\r\n"
        "Connection: close\r\n"
        "\r\n";

    int result = http_client_t.request(
        "127.0.0.1",
        18081,
        request,
        3000,
        response,
        sizeof(response),
        &response_length,
        &native_error
    );
    if (result != HTTP_CLIENT_OK) {
        fprintf(stderr, "HTTP request failed (client error %d, native error %d)\n",
                result, native_error);
        return 1;
    }

    if (fwrite(response, 1, response_length, stdout) != response_length) {
        fprintf(stderr, "could not write the HTTP response\n");
        return 1;
    }
    return 0;
}
