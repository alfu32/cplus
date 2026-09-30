#!/bin/zsh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
export CPLUS_HOME="$SCRIPT_DIR"
JAVA_BIN="${CPLUS_JAVA:-}"
if [[ -z "$JAVA_BIN" ]]; then
    for JAVA_HOME_CANDIDATE in "${JAVA_HOME:-}" "${IDEA_JDK:-}" "${JDK_HOME:-}"; do
        if [[ -n "$JAVA_HOME_CANDIDATE" && -x "$JAVA_HOME_CANDIDATE/bin/java" ]]; then
            JAVA_BIN="$JAVA_HOME_CANDIDATE/bin/java"
            break
        fi
    done
fi
if [[ -z "$JAVA_BIN" ]]; then
    JAVA_BIN="$(command -v java 2>/dev/null || true)"
fi
if [[ -z "$JAVA_BIN" || ! -x "$JAVA_BIN" ]]; then
    echo "c-plus: Java runtime not found; set CPLUS_JAVA or JAVA_HOME" >&2
    exit 127
fi
exec "$JAVA_BIN" -Dcplus.home="$SCRIPT_DIR" -jar "$SCRIPT_DIR/c-plus.jar" "$@"
