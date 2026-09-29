#!/bin/sh
state_path=$1
count=0
if [ -f "$state_path" ]; then
    count=$(cat "$state_path")
fi
count=$((count + 1))
printf '%s' "$count" > "$state_path"

read_frame() {
    header=""
    while IFS= read -r line; do
        line=$(printf '%s' "$line" | tr -d '\r')
        if [ -z "$line" ]; then
            break
        fi
        header=$line
    done
    length=$(printf '%s' "$header" | sed -n 's/^Content-Length: *\([0-9][0-9]*\)$/\1/p')
    [ -n "$length" ] || return 1
    body=$(dd bs=1 count="$length" 2>/dev/null)
    printf '%s' "$body"
}

respond() {
    id=$(printf '%s' "$1" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
    response=$(printf '{"jsonrpc":"2.0","id":%s,"result":null}' "$id")
    printf 'Content-Length: %s\r\n\r\n%s' "${#response}" "$response"
}

message=$(read_frame) || exit 0
respond "$message"
if [ "$count" -eq 1 ]; then
    exit 23
fi

while message=$(read_frame); do
    case "$message" in
        *'"method":"shutdown"'*) exit 0 ;;
    esac
done
