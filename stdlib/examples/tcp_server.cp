/*
 * A single-client TCP echo server. It binds port 9090, echoes bytes until
 * the client closes its connection, then exits.
 *
 * Try it with:
 *   cpc run stdlib/examples/tcp_server.cp
 *   nc 127.0.0.1 9090
 */
comptime import "stdlib:/net/socket.cp";

#include <stdio.h>

#define TCP_EXAMPLE_PORT 9090
#define TCP_EXAMPLE_BUFFER_SIZE 1024

int main(void) {
    if (net_socket_t.startup() != NET_SOCKET_OK) {
        fprintf(stderr, "socket startup failed (error %d)\n", net_socket_t.last_error());
        return 1;
    }

    net_socket_handle_t listener = NET_INVALID_SOCKET;
    net_socket_handle_t client = NET_INVALID_SOCKET;
    unsigned short bound_port = 0;
    int native_error = 0;
    int failed = 0;

    if (net_socket_t.bind_listen_ipv4(
            TCP_EXAMPLE_PORT, 8, &listener, &bound_port, &native_error) != NET_SOCKET_OK) {
        fprintf(stderr, "could not listen on port %d (error %d)\n",
                TCP_EXAMPLE_PORT, native_error);
        failed = 1;
    } else {
        printf("TCP echo server listening on port %u; connect with nc 127.0.0.1 %u\n",
               (unsigned int)bound_port, (unsigned int)bound_port);
        fflush(stdout);
    }

    while (!failed && client == NET_INVALID_SOCKET) {
        int ready = net_socket_t.wait(listener, 0, 5000);
        if (ready == NET_SOCKET_TIMEOUT) continue;
        if (ready == NET_SOCKET_ERROR) {
            native_error = net_socket_t.last_error();
            fprintf(stderr, "waiting for a client failed (error %d)\n", native_error);
            failed = 1;
            break;
        }

        client = net_socket_t.accept_one(listener, &native_error);
        if (client == NET_INVALID_SOCKET && !net_socket_t.is_would_block(native_error)) {
            fprintf(stderr, "accept failed (error %d)\n", native_error);
            failed = 1;
        }
    }

    if (!failed && client != NET_INVALID_SOCKET) {
        char buffer[TCP_EXAMPLE_BUFFER_SIZE];
        printf("client connected; echoing until it closes\n");
        fflush(stdout);

        for (;;) {
            int ready = net_socket_t.wait(client, 0, 30000);
            if (ready == NET_SOCKET_TIMEOUT) {
                fprintf(stderr, "client idle timeout\n");
                failed = 1;
                break;
            }
            if (ready == NET_SOCKET_ERROR) {
                fprintf(stderr, "waiting for client data failed (error %d)\n",
                        net_socket_t.last_error());
                failed = 1;
                break;
            }

            long received = net_socket_t.receive(client, buffer, sizeof(buffer));
            if (received == 0) break;
            if (received < 0) {
                native_error = net_socket_t.last_error();
                if (net_socket_t.is_would_block(native_error)) continue;
                fprintf(stderr, "receive failed (error %d)\n", native_error);
                failed = 1;
                break;
            }

            size_t sent = 0;
            while (sent < (size_t)received) {
                long count = net_socket_t.send(client, buffer + sent, (size_t)received - sent);
                if (count > 0) {
                    sent += (size_t)count;
                    continue;
                }
                if (count < 0) {
                    native_error = net_socket_t.last_error();
                    if (net_socket_t.is_would_block(native_error)) {
                        int writable = net_socket_t.wait(client, 1, 30000);
                        if (writable == NET_SOCKET_READY) continue;
                        fprintf(stderr, "waiting to echo data failed (error %d)\n",
                                net_socket_t.last_error());
                    } else {
                        fprintf(stderr, "send failed (error %d)\n", native_error);
                    }
                }
                failed = 1;
                break;
            }
            if (failed) break;
        }
    }

    if (client != NET_INVALID_SOCKET) net_socket_t.close(client);
    if (listener != NET_INVALID_SOCKET) net_socket_t.close(listener);
    net_socket_t.cleanup();
    return failed ? 1 : 0;
}
