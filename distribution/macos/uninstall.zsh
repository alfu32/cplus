#!/bin/zsh
set -e
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd -P)"
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
    print "C-plus $VERSION is not installed in the standard macOS locations."
    exit 0
fi
case "$APP_DIR" in
    "/usr/local/lib/cplus/$VERSION"|"$HOME/.local/opt/cplus/$VERSION") ;;
    *) print -u2 "Refusing to remove unexpected path: $APP_DIR"; exit 2 ;;
esac
APP_BUNDLE="$APP_DIR/C-plus.app"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
if [[ -d "$APP_BUNDLE" && -x "$LSREGISTER" ]]; then
    "$LSREGISTER" -u "$APP_BUNDLE" >/dev/null 2>&1 || true
fi
if [[ -f "$LAUNCHER" ]] && grep -Fq "# CPLUS_INSTALL_VERSION=$VERSION" "$LAUNCHER"; then
    rm -f "$LAUNCHER"
fi
rm -rf -- "$APP_DIR"
print "Removed C-plus $VERSION from $APP_DIR"
if [[ "$LAUNCHER" == "$HOME/.local/bin/cpc" ]]; then
    print 'If you added ~/.local/bin to ~/.zshrc only for C-plus, you may remove that PATH line.'
fi
