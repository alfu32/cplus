#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
export CPLUS_HOME="$SCRIPT_DIR"
exec java -Dcplus.home="$SCRIPT_DIR" -jar "$SCRIPT_DIR/c-plus.jar" "$@"
