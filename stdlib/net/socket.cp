#ifndef CPLUS_STDLIB_NET_SOCKET_CP
#define CPLUS_STDLIB_NET_SOCKET_CP

#if !defined(_WIN32) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200112L
#endif

#include <stddef.h>
#include <limits.h>
#include <string.h>

comptime {
    @if (os == "windows") {
        comptime flags -lws2_32;
    }
}

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
typedef SOCKET net_socket_handle_t;
#define NET_INVALID_SOCKET INVALID_SOCKET
#define NET_SOCKET_FAILED SOCKET_ERROR
#else
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
typedef int net_socket_handle_t;
#define NET_INVALID_SOCKET (-1)
#define NET_SOCKET_FAILED (-1)
#endif

enum {
    NET_SOCKET_OK = 0,
    NET_SOCKET_ERROR = -1,
    NET_SOCKET_TIMEOUT = 0,
    NET_SOCKET_READY = 1
};

typedef struct net_socket_t {
    static pub int startup(void) {
#if defined(_WIN32)
        WSADATA data;
        return WSAStartup(MAKEWORD(2, 2), &data) == 0 ? NET_SOCKET_OK : NET_SOCKET_ERROR;
#else
        return NET_SOCKET_OK;
#endif
    }

    static pub void cleanup(void) {
#if defined(_WIN32)
        WSACleanup();
#endif
    }

    static pub int last_error(void) {
#if defined(_WIN32)
        return WSAGetLastError();
#else
        return errno;
#endif
    }

    static pub int is_would_block(int error_code) {
#if defined(_WIN32)
        return error_code == WSAEWOULDBLOCK;
#else
        return error_code == EAGAIN || error_code == EWOULDBLOCK;
#endif
    }

    static pub int is_connect_pending(int error_code) {
#if defined(_WIN32)
        return error_code == WSAEWOULDBLOCK || error_code == WSAEINPROGRESS || error_code == WSAEALREADY;
#else
        return error_code == EINPROGRESS || error_code == EALREADY || error_code == EWOULDBLOCK || error_code == EAGAIN;
#endif
    }

    static pub int set_nonblocking(net_socket_handle_t fd) {
        if (fd == NET_INVALID_SOCKET) return NET_SOCKET_ERROR;
#if defined(_WIN32)
        u_long enabled = 1;
        return ioctlsocket(fd, FIONBIO, &enabled) == 0 ? NET_SOCKET_OK : NET_SOCKET_ERROR;
#else
        int flags = fcntl(fd, F_GETFL, 0);
        if (flags < 0) return NET_SOCKET_ERROR;
        if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) return NET_SOCKET_ERROR;
#if !defined(MSG_NOSIGNAL) && defined(SO_NOSIGPIPE)
        int no_sigpipe = 1;
        if (setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &no_sigpipe, (socklen_t)sizeof(no_sigpipe)) != 0)
            return NET_SOCKET_ERROR;
#endif
        return NET_SOCKET_OK;
#endif
    }

    static pub int close(net_socket_handle_t fd) {
        if (fd == NET_INVALID_SOCKET) return NET_SOCKET_OK;
#if defined(_WIN32)
        return closesocket(fd) == 0 ? NET_SOCKET_OK : NET_SOCKET_ERROR;
#else
        return close(fd) == 0 ? NET_SOCKET_OK : NET_SOCKET_ERROR;
#endif
    }

    static pub long receive(net_socket_handle_t fd, void* buffer, size_t capacity) {
        if (buffer == NULL || capacity == 0) return 0;
#if defined(_WIN32)
        int amount = capacity > (size_t)INT_MAX ? INT_MAX : (int)capacity;
        return (long)recv(fd, (char*)buffer, amount, 0);
#else
        return (long)recv(fd, buffer, capacity, 0);
#endif
    }

    static pub long send(net_socket_handle_t fd, borrowed const void* buffer, size_t length) {
        if (buffer == NULL || length == 0) return 0;
#if defined(_WIN32)
        int amount = length > (size_t)INT_MAX ? INT_MAX : (int)length;
        return (long)send(fd, (const char*)buffer, amount, 0);
#else
        int flags = 0;
#ifdef MSG_NOSIGNAL
        flags = MSG_NOSIGNAL;
#endif
        size_t amount = length > (size_t)INT_MAX ? (size_t)INT_MAX : length;
        return (long)send(fd, buffer, amount, flags);
#endif
    }

    static pub int wait(net_socket_handle_t fd, int writable, unsigned int timeout_ms) {
        if (fd == NET_INVALID_SOCKET) return NET_SOCKET_ERROR;
#if !defined(_WIN32)
        if (fd >= FD_SETSIZE) return NET_SOCKET_ERROR;
#endif
        fd_set ready_set;
        FD_ZERO(&ready_set);
        FD_SET(fd, &ready_set);
        struct timeval timeout;
        timeout.tv_sec = (long)(timeout_ms / 1000U);
        timeout.tv_usec = (long)(timeout_ms % 1000U) * 1000L;
        int selected;
#if defined(_WIN32)
        if (writable) selected = select(0, NULL, &ready_set, NULL, &timeout);
        else selected = select(0, &ready_set, NULL, NULL, &timeout);
#else
        if (writable) selected = select(fd + 1, NULL, &ready_set, NULL, &timeout);
        else selected = select(fd + 1, &ready_set, NULL, NULL, &timeout);
#endif
        if (selected > 0) return NET_SOCKET_READY;
        if (selected == 0) return NET_SOCKET_TIMEOUT;
        return NET_SOCKET_ERROR;
    }

    static pub unsigned long long now_ms(void) {
#if defined(_WIN32)
        return (unsigned long long)GetTickCount64();
#else
        struct timeval now;
        if (gettimeofday(&now, NULL) != 0) return 0;
        return (unsigned long long)now.tv_sec * 1000ULL + (unsigned long long)now.tv_usec / 1000ULL;
#endif
    }

    static pub net_socket_handle_t create_tcp(int address_family) {
        return socket(address_family, SOCK_STREAM, IPPROTO_TCP);
    }

    static pub int socket_error(net_socket_handle_t fd, borrowed mut int* error_code) {
        if (fd == NET_INVALID_SOCKET || error_code == NULL) return NET_SOCKET_ERROR;
#if defined(_WIN32)
        int length = (int)sizeof(*error_code);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, (char*)error_code, &length) != 0) return NET_SOCKET_ERROR;
#else
        socklen_t length = (socklen_t)sizeof(*error_code);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, error_code, &length) != 0) return NET_SOCKET_ERROR;
#endif
        return NET_SOCKET_OK;
    }

    static pub int bind_listen_ipv4(
        unsigned short requested_port,
        int backlog,
        borrowed mut net_socket_handle_t* out_fd,
        borrowed mut unsigned short* out_port,
        borrowed mut int* native_error
    ) {
        if (out_fd == NULL || out_port == NULL || backlog < 1) return NET_SOCKET_ERROR;
        *out_fd = NET_INVALID_SOCKET;
        *out_port = 0;
        net_socket_handle_t fd = net_socket_t.create_tcp(AF_INET);
        if (fd == NET_INVALID_SOCKET) {
            if (native_error != NULL) *native_error = net_socket_t.last_error();
            return NET_SOCKET_ERROR;
        }

        int reuse = 1;
#if defined(_WIN32)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (const char*)&reuse, (int)sizeof(reuse));
#else
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, (socklen_t)sizeof(reuse));
#endif
        struct sockaddr_in address;
        memset(&address, 0, sizeof(address));
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_ANY);
        address.sin_port = htons(requested_port);
#if defined(_WIN32)
        int address_length = (int)sizeof(address);
#else
        socklen_t address_length = (socklen_t)sizeof(address);
#endif
        if (bind(fd, (struct sockaddr*)&address, address_length) != 0 ||
            listen(fd, backlog) != 0 ||
            net_socket_t.set_nonblocking(fd) != NET_SOCKET_OK ||
            getsockname(fd, (struct sockaddr*)&address, &address_length) != 0) {
            if (native_error != NULL) *native_error = net_socket_t.last_error();
            net_socket_t.close(fd);
            return NET_SOCKET_ERROR;
        }
        *out_fd = fd;
        *out_port = ntohs(address.sin_port);
        if (native_error != NULL) *native_error = 0;
        return NET_SOCKET_OK;
    }

    static pub net_socket_handle_t accept_one(net_socket_handle_t listener, borrowed mut int* native_error) {
#if defined(_WIN32)
        int address_length = (int)sizeof(struct sockaddr_storage);
#else
        socklen_t address_length = (socklen_t)sizeof(struct sockaddr_storage);
#endif
        struct sockaddr_storage address;
        net_socket_handle_t accepted = accept(listener, (struct sockaddr*)&address, &address_length);
        if (accepted == NET_INVALID_SOCKET) {
            if (native_error != NULL) *native_error = net_socket_t.last_error();
            return NET_INVALID_SOCKET;
        }
        if (net_socket_t.set_nonblocking(accepted) != NET_SOCKET_OK) {
            if (native_error != NULL) *native_error = net_socket_t.last_error();
            net_socket_t.close(accepted);
            return NET_INVALID_SOCKET;
        }
        if (native_error != NULL) *native_error = 0;
        return accepted;
    }
} net_socket_t;

#endif
