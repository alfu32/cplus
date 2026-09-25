#!/bin/zsh
set -e
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd -P)"
while [[ ! -f "$SOURCE_DIR/VERSION" && "$SOURCE_DIR" != / ]]; do SOURCE_DIR="$(dirname "$SOURCE_DIR")"; done
VERSION="$(<"$SOURCE_DIR/VERSION")"
case "$VERSION" in *[!A-Za-z0-9._+-]*|'') print -u2 "Invalid bundle VERSION"; exit 2 ;; esac

probe_directory() {
    local directory="$1" probe
    mkdir -p "$directory" 2>/dev/null || return 1
    probe="$directory/.cplus-write-test-$$"
    (umask 077; : > "$probe") 2>/dev/null || return 1
    rm -f "$probe"
}

if probe_directory /usr/local/lib/cplus && probe_directory /usr/local/bin; then
    APP_DIR="/usr/local/lib/cplus/$VERSION"
    BIN_DIR=/usr/local/bin
    INSTALL_KIND=system
else
    APP_DIR="$HOME/.local/opt/cplus/$VERSION"
    BIN_DIR="$HOME/.local/bin"
    mkdir -p "$APP_DIR" "$BIN_DIR"
    INSTALL_KIND=user
fi

mkdir -p "$APP_DIR"
if [[ "$(cd "$SOURCE_DIR" && pwd -P)" != "$(cd "$APP_DIR" && pwd -P)" ]]; then
    cp -R "$SOURCE_DIR"/. "$APP_DIR"/
fi
chmod +x "$APP_DIR/cpc.zsh"
APP_BUNDLE="$APP_DIR/C-plus.app"
if [[ -d "$APP_BUNDLE" ]]; then
    chmod +x "$APP_BUNDLE/Contents/MacOS/cplus-open"
    LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
    if [[ -x "$LSREGISTER" ]]; then
        "$LSREGISTER" -f "$APP_BUNDLE" >/dev/null 2>&1 || true
    fi
fi

LAUNCHER="$BIN_DIR/cpc"
if [[ -e "$LAUNCHER" ]] && ! grep -Fq '# CPLUS_INSTALL_VERSION=' "$LAUNCHER"; then
    print -u2 "Refusing to overwrite an existing non-C-plus launcher: $LAUNCHER"
    exit 2
fi
TEMP_LAUNCHER="$BIN_DIR/.cpc-install-$$"
{
    print '#!/bin/zsh'
    print "# CPLUS_INSTALL_VERSION=$VERSION"
    printf 'export CPLUS_HOME=%q\n' "$APP_DIR"
    printf 'exec %q/cpc.zsh "$@"\n' "$APP_DIR"
} > "$TEMP_LAUNCHER"
chmod +x "$TEMP_LAUNCHER"
mv -f "$TEMP_LAUNCHER" "$LAUNCHER"

print "C-plus $VERSION installed ($INSTALL_KIND) to $APP_DIR"
if [[ ":${PATH:-}:" != *":$BIN_DIR:"* ]]; then
    print 'Add this line to ~/.zshrc, then open a new terminal:'
    printf '  export PATH="%s:$PATH"\n' "$BIN_DIR"
fi
print "Launcher: $LAUNCHER"
print 'Registered C-plus source file icons as an alternate Viewer; your default editor remains unchanged.'
