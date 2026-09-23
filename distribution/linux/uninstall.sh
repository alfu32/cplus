#!/usr/bin/env bash
set -euo pipefail
SOURCE_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
while [[ ! -f "$SOURCE_DIR/VERSION" && "$SOURCE_DIR" != / ]]; do SOURCE_DIR="$(dirname "$SOURCE_DIR")"; done
VERSION="$(<"$SOURCE_DIR/VERSION")"
SYSTEM_APP="/usr/local/lib/cplus/$VERSION"
USER_APP="$HOME/.local/opt/cplus/$VERSION"
SYSTEM_LAUNCHER=/usr/local/bin/cpc
USER_LAUNCHER="$HOME/.local/bin/cpc"

if [[ -d "$SYSTEM_APP" ]]; then
    APP_DIR="$SYSTEM_APP"
    LAUNCHER="$SYSTEM_LAUNCHER"
elif [[ -d "$USER_APP" ]]; then
    APP_DIR="$USER_APP"
    LAUNCHER="$USER_LAUNCHER"
else
    echo "C-plus $VERSION is not installed in the standard Linux locations."
    exit 0
fi

case "$APP_DIR" in
    "/usr/local/lib/cplus/$VERSION"|"$HOME/.local/opt/cplus/$VERSION") ;;
    *) echo "Refusing to remove unexpected path: $APP_DIR" >&2; exit 2 ;;
esac
if [[ -f "$LAUNCHER" ]] && grep -Fq "# CPLUS_INSTALL_VERSION=$VERSION" "$LAUNCHER"; then
    rm -f "$LAUNCHER"
fi
rm -rf -- "$APP_DIR"
echo "Removed C-plus $VERSION from $APP_DIR"
if [[ "$LAUNCHER" == "$HOME/.local/bin/cpc" ]]; then
    echo "If you added ~/.local/bin to ~/.bashrc only for C-plus, you may remove that PATH line."
fi
