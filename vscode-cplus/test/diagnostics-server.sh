#!/bin/sh

read_frame() {
    header=""
    while IFS= read -r line; do
        line=$(printf '%s' "$line" | tr -d '\r')
        [ -z "$line" ] && break
        header=$line
    done
    length=$(printf '%s' "$header" | sed -n 's/^Content-Length: *\([0-9][0-9]*\)$/\1/p')
    [ -n "$length" ] || return 1
    dd bs=1 count="$length" 2>/dev/null
}

send() {
    body=$1
    printf 'Content-Length: %s\r\n\r\n%s' "${#body}" "$body"
}

initialize=$(read_frame) || exit 0
id=$(printf '%s' "$initialize" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
send "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{}}"
initialized=$(read_frame) || exit 0
send '{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///diagnostics.cp","diagnostics":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":4}},"severity":2,"code":"CPLUS_UNSUPPORTED_AST","message":"unmapped AST fragment"},{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":3}},"severity":1,"code":"CPLUS_PARSE","message":"parse error"}]}}'

while message=$(read_frame); do
    case "$message" in
        *'"method":"shutdown"'*)
            shutdown_id=$(printf '%s' "$message" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
            send "{\"jsonrpc\":\"2.0\",\"id\":$shutdown_id,\"result\":null}"
            exit 0
            ;;
    esac
done
