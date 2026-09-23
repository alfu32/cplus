#!/bin/zsh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
export CPLUS_HOME="$SCRIPT_DIR"
exec java -Dcplus.home="$SCRIPT_DIR" -jar "$SCRIPT_DIR/c-plus.jar" "$@"
