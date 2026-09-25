#ifndef CPLUS_STDLIB_HTTP_PROTOCOL_CP
#define CPLUS_STDLIB_HTTP_PROTOCOL_CP

#include <stddef.h>
#include <string.h>

enum {
    HTTP_PROTOCOL_NEED_MORE = 0,
    HTTP_PROTOCOL_COMPLETE = 1,
    HTTP_PROTOCOL_INVALID = -1,
    HTTP_PROTOCOL_UNSUPPORTED = -2,
    HTTP_PROTOCOL_TOO_LARGE = -3
};

typedef struct http_protocol_t {
    static priv int valid_request_line(borrowed const char* bytes, size_t line_end) {
        if (bytes == NULL || line_end < 12) return 0;
        size_t method_end = 0;
        while (method_end < line_end && bytes[method_end] != ' ') {
            char value = bytes[method_end];
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') ||
                  (value >= '0' && value <= '9') || value == '-' || value == '_')) return 0;
            method_end++;
        }
        if (method_end == 0 || method_end >= line_end) return 0;
        size_t path_start = method_end + 1;
        size_t path_end = path_start;
        while (path_end < line_end && bytes[path_end] != ' ') path_end++;
        if (path_end == path_start || path_end >= line_end || bytes[path_start] != '/') return 0;
        if (line_end - path_end - 1 != 8 || memcmp(bytes + path_end + 1, "HTTP/1.1", 8) != 0) return 0;
        return 1;
    }

    static priv int find_header_end(borrowed const char* bytes, size_t length, borrowed mut size_t* out_end) {
        if (bytes == NULL || out_end == NULL) return HTTP_PROTOCOL_INVALID;
        if (length < 4) return HTTP_PROTOCOL_NEED_MORE;
        for (size_t index = 0; index + 3 < length; index++) {
            if (bytes[index] == '\r' && bytes[index + 1] == '\n' &&
                bytes[index + 2] == '\r' && bytes[index + 3] == '\n') {
                *out_end = index + 4;
                return HTTP_PROTOCOL_COMPLETE;
            }
        }
        return HTTP_PROTOCOL_NEED_MORE;
    }

    static priv int ascii_equal_lower(borrowed const char* left, size_t length, borrowed const char* right) {
        size_t right_length = strlen(right);
        if (length != right_length) return 0;
        for (size_t index = 0; index < length; index++) {
            char a = left[index];
            char b = right[index];
            if (a >= 'A' && a <= 'Z') a = (char)(a - 'A' + 'a');
            if (b >= 'A' && b <= 'Z') b = (char)(b - 'A' + 'a');
            if (a != b) return 0;
        }
        return 1;
    }

    static priv int parse_decimal(borrowed const char* bytes, size_t length, borrowed mut size_t* out_value) {
        if (length == 0 || out_value == NULL) return 0;
        size_t value = 0;
        for (size_t index = 0; index < length; index++) {
            if (bytes[index] < '0' || bytes[index] > '9') return 0;
            size_t digit = (size_t)(bytes[index] - '0');
            if (value > ((size_t)-1 - digit) / 10) return 0;
            value = value * 10 + digit;
        }
        *out_value = value;
        return 1;
    }

    static priv int parse_headers(
        borrowed const char* bytes,
        size_t header_end,
        borrowed mut int* has_content_length,
        borrowed mut size_t* content_length,
        borrowed mut int* is_chunked
    ) {
        if (bytes == NULL || has_content_length == NULL || content_length == NULL || is_chunked == NULL) {
            return HTTP_PROTOCOL_INVALID;
        }
        *has_content_length = 0;
        *content_length = 0;
        *is_chunked = 0;

        size_t line_start = 0;
        while (line_start + 1 < header_end && !(bytes[line_start] == '\r' && bytes[line_start + 1] == '\n'))
            line_start++;
        if (line_start + 1 >= header_end) return HTTP_PROTOCOL_INVALID;
        line_start += 2;

        while (line_start + 1 < header_end) {
            if (bytes[line_start] == '\r' && bytes[line_start + 1] == '\n') break;
            size_t line_end = line_start;
            while (line_end + 1 < header_end && !(bytes[line_end] == '\r' && bytes[line_end + 1] == '\n'))
                line_end++;
            if (line_end + 1 >= header_end) return HTTP_PROTOCOL_INVALID;
            size_t colon = line_start;
            while (colon < line_end && bytes[colon] != ':') colon++;
            if (colon == line_start || colon == line_end) return HTTP_PROTOCOL_INVALID;

            size_t value_start = colon + 1;
            while (value_start < line_end && (bytes[value_start] == ' ' || bytes[value_start] == '\t')) value_start++;
            size_t value_end = line_end;
            while (value_end > value_start && (bytes[value_end - 1] == ' ' || bytes[value_end - 1] == '\t')) value_end--;

            if (http_protocol_t.ascii_equal_lower(bytes + line_start, colon - line_start, "content-length")) {
                size_t parsed = 0;
                if (!http_protocol_t.parse_decimal(bytes + value_start, value_end - value_start, &parsed))
                    return HTTP_PROTOCOL_INVALID;
                if (*has_content_length && parsed != *content_length) return HTTP_PROTOCOL_INVALID;
                *has_content_length = 1;
                *content_length = parsed;
            } else if (http_protocol_t.ascii_equal_lower(bytes + line_start, colon - line_start, "transfer-encoding")) {
                if (value_end > value_start) *is_chunked = 1;
            }
            line_start = line_end + 2;
        }
        return HTTP_PROTOCOL_COMPLETE;
    }

    static pub int request_complete(
        borrowed const char* bytes,
        size_t length,
        size_t capacity,
        borrowed mut size_t* out_header_end
    ) {
        size_t header_end = 0;
        int header_status = http_protocol_t.find_header_end(bytes, length, &header_end);
        if (header_status != HTTP_PROTOCOL_COMPLETE) return header_status;
        size_t line_end = 0;
        while (line_end + 1 < header_end && !(bytes[line_end] == '\r' && bytes[line_end + 1] == '\n'))
            line_end++;
        if (line_end + 1 >= header_end || !http_protocol_t.valid_request_line(bytes, line_end))
            return HTTP_PROTOCOL_INVALID;
        int has_length = 0;
        int chunked = 0;
        size_t content_length = 0;
        int parsed = http_protocol_t.parse_headers(bytes, header_end, &has_length, &content_length, &chunked);
        if (parsed != HTTP_PROTOCOL_COMPLETE) return parsed;
        if (chunked) return HTTP_PROTOCOL_UNSUPPORTED;
        if (header_end > capacity) return HTTP_PROTOCOL_TOO_LARGE;
        if (content_length > capacity - header_end) return HTTP_PROTOCOL_TOO_LARGE;
        if (out_header_end != NULL) *out_header_end = header_end;
        return length >= header_end + (has_length ? content_length : 0)
            ? HTTP_PROTOCOL_COMPLETE
            : HTTP_PROTOCOL_NEED_MORE;
    }

    static pub int response_framing(
        borrowed const char* bytes,
        size_t length,
        borrowed mut size_t* out_header_end,
        borrowed mut size_t* out_total_length,
        borrowed mut int* out_close_delimited
    ) {
        size_t header_end = 0;
        int header_status = http_protocol_t.find_header_end(bytes, length, &header_end);
        if (header_status != HTTP_PROTOCOL_COMPLETE) return header_status;
        int has_length = 0;
        int chunked = 0;
        size_t content_length = 0;
        int parsed = http_protocol_t.parse_headers(bytes, header_end, &has_length, &content_length, &chunked);
        if (parsed != HTTP_PROTOCOL_COMPLETE) return parsed;
        if (chunked) return HTTP_PROTOCOL_UNSUPPORTED;
        if (has_length && content_length > (size_t)-1 - header_end) return HTTP_PROTOCOL_TOO_LARGE;
        if (out_header_end != NULL) *out_header_end = header_end;
        if (out_close_delimited != NULL) *out_close_delimited = !has_length;
        if (out_total_length != NULL) *out_total_length = has_length ? header_end + content_length : 0;
        return HTTP_PROTOCOL_COMPLETE;
    }

    static pub int route_matches(
        borrowed const char* request,
        size_t request_length,
        borrowed const char* method,
        borrowed const char* path
    ) {
        if (request == NULL || method == NULL || path == NULL) return 0;
        size_t line_end = 0;
        while (line_end + 1 < request_length && !(request[line_end] == '\r' && request[line_end + 1] == '\n'))
            line_end++;
        if (line_end + 1 >= request_length || !http_protocol_t.valid_request_line(request, line_end)) return 0;

        size_t method_end = 0;
        while (method_end < line_end && request[method_end] != ' ') method_end++;
        if (method_end == 0 || method_end >= line_end) return 0;
        size_t path_start = method_end + 1;
        size_t path_end = path_start;
        while (path_end < line_end && request[path_end] != ' ') path_end++;
        if (path_end == path_start || path_end >= line_end) return 0;

        size_t query = path_start;
        while (query < path_end && request[query] != '?') query++;
        return strlen(method) == method_end &&
            memcmp(request, method, method_end) == 0 &&
            strlen(path) == query - path_start &&
            memcmp(request + path_start, path, query - path_start) == 0;
    }
} http_protocol_t;

#endif
