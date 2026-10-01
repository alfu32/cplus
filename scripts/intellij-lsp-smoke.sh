#!/usr/bin/env bash
set -euo pipefail

# Launch the installed IntelliJ IDEA against an isolated profile, install the
# packaged C-plus plugin, and verify the real LSP client reaches Running state.
# The isolated profile enables IntelliJ's LSP debug category so future feature
# and crash/restart assertions can inspect client/server lifecycle records.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plugin_zip="${1:-}"
source_file="${2:-}"
idea_launcher="${IDEA_LAUNCHER:-}"
timeout_seconds="${INTELLIJ_SMOKE_TIMEOUT:-180}"
restart_probe="${INTELLIJ_SMOKE_RESTART:-0}"

if [[ -z "$plugin_zip" ]]; then
    mapfile -t plugin_candidates < <(find "$repo_root/intellij-cplus/build/distributions" -maxdepth 1 -type f -name '*.zip' -printf '%T@ %p\n' 2>/dev/null | sort -nr | sed 's/^[^ ]* //')
    plugin_zip="${plugin_candidates[0]:-}"
fi

if [[ -z "$idea_launcher" ]]; then
    if command -v idea >/dev/null 2>&1; then
        idea_launcher="$(command -v idea)"
    elif [[ -x "/home/devlin/.local/share/JetBrains/Toolbox/scripts/idea" ]]; then
        idea_launcher="/home/devlin/.local/share/JetBrains/Toolbox/scripts/idea"
    else
        echo "IntelliJ launcher not found; set IDEA_LAUNCHER" >&2
        exit 2
    fi
fi

command -v unzip >/dev/null 2>&1 || { echo "unzip is required" >&2; exit 2; }
command -v timeout >/dev/null 2>&1 || { echo "timeout is required" >&2; exit 2; }
command -v xvfb-run >/dev/null 2>&1 || { echo "xvfb-run is required" >&2; exit 2; }
[[ -f "$plugin_zip" ]] || { echo "plugin ZIP not found: $plugin_zip" >&2; exit 2; }

smoke_dir="$(mktemp -d "${TMPDIR:-/tmp}/cplus-intellij-smoke.XXXXXX")"
keep_dir=0
smoke_launcher_pid=""
smoke_server_pid=""
cleanup() {
    if [[ -n "$smoke_server_pid" ]] && kill -0 "$smoke_server_pid" 2>/dev/null; then
        kill -TERM "$smoke_server_pid" 2>/dev/null || true
    fi
    if [[ -n "$smoke_launcher_pid" ]] && kill -0 "$smoke_launcher_pid" 2>/dev/null; then
        kill -TERM "$smoke_launcher_pid" 2>/dev/null || true
    fi
    if (( keep_dir )); then
        echo "IntelliJ smoke files retained at: $smoke_dir" >&2
    else
        rm -rf "$smoke_dir" 2>/dev/null || true
    fi
}
trap cleanup EXIT

mkdir -p "$smoke_dir/config/plugins"
unzip -q -o "$plugin_zip" -d "$smoke_dir/config/plugins"
# Keep the smoke profile lightweight. C-plus depends on the LSP platform module.
# Ignore inherited disabled-plugin state so an Ultimate installation does not
# silently disable its own LSP platform in the isolated profile.
printf '%s\n' 'com.intellij.classic.ui' > "$smoke_dir/config/disabled_plugins.txt"
: > "$smoke_dir/config/bundled_plugins.txt"
cat > "$smoke_dir/idea.properties" <<EOF
idea.config.path=$smoke_dir/config
idea.system.path=$smoke_dir/system
idea.log.debug.categories=#com.intellij.platform.lsp
idea.ignore.disabled.plugins=true
EOF
if [[ -z "$source_file" ]]; then
    smoke_project="$smoke_dir/project"
    mkdir -p "$smoke_project/.idea"
    printf '%s\n' '<project version="4"><component name="ProjectRootManager" version="2" /></project>' > "$smoke_project/.idea/misc.xml"
    source_file="$smoke_project/smoke.cp"
    printf '%s\n' 'int main(void) { return 0; }' > "$source_file"
fi
[[ -f "$source_file" ]] || { echo "C-plus source not found: $source_file" >&2; exit 2; }

path_value="$PATH"
if [[ -d "$repo_root/c-plus-bin" ]]; then
    path_value="$repo_root/c-plus-bin:$path_value"
fi
trace_file="$smoke_dir/lsp.trace"
lifecycle_file="$smoke_dir/lsp.lifecycle"

if [[ "$restart_probe" == "1" ]]; then
    # Keep the launcher asynchronous so the exact LSP child PID can be
    # terminated and IntelliJ's own client recovery can be observed. The
    # lifecycle file is opt-in and inherited only by this isolated process.
    set +e
    timeout "$timeout_seconds" xvfb-run -a env PATH="$path_value" \
        CPLUS_LSP_TRACE="$trace_file" CPLUS_LSP_LIFECYCLE_FILE="$lifecycle_file" \
        IDEA_PROPERTIES="$smoke_dir/idea.properties" "$idea_launcher" \
        "$source_file" >"$smoke_dir/launcher.log" 2>&1 &
    smoke_launcher_pid=$!
    set -e

    startup_deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < startup_deadline )); do
        if [[ -f "$lifecycle_file" ]] && grep -Fq 'state=running' "$lifecycle_file" \
            && grep -Fq 'LSP server initialized' "$smoke_dir/system/log/idea.log" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    if [[ ! -f "$lifecycle_file" ]] || ! grep -Fq 'state=running' "$lifecycle_file"; then
        keep_dir=1
        echo "IntelliJ LSP did not publish a running lifecycle marker" >&2
        exit 1
    fi
    initial_pid="$(sed -n 's/^pid=\([0-9][0-9]*\) state=.*/\1/p' "$lifecycle_file")"
    if [[ -z "$initial_pid" ]] || ! kill -0 "$initial_pid" 2>/dev/null; then
        keep_dir=1
        echo "IntelliJ LSP lifecycle marker did not identify a live process" >&2
        exit 1
    fi
    smoke_server_pid="$initial_pid"

    # A forced child exit is intentional here: it distinguishes IntelliJ
    # recovery from a clean protocol shutdown. Never use a name-based kill.
    kill -KILL "$initial_pid"
    smoke_server_pid=""
    restart_deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < restart_deadline )); do
        restarted_pid="$(sed -n 's/^pid=\([0-9][0-9]*\) state=running/\1/p' "$lifecycle_file" 2>/dev/null || true)"
        initialize_count="$(grep -c 'in method=initialize' "$trace_file" 2>/dev/null || true)"
        if [[ -n "$restarted_pid" && "$restarted_pid" != "$initial_pid" \
            && "$initialize_count" -ge 2 ]] && kill -0 "$restarted_pid" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    if [[ -z "${restarted_pid:-}" || "$restarted_pid" == "$initial_pid" \
        || "${initialize_count:-0}" -lt 2 ]]; then
        keep_dir=1
        echo "IntelliJ LSP client did not restart after the exact server process exited" >&2
        exit 1
    fi
    smoke_server_pid="$restarted_pid"
    kill -TERM "$smoke_launcher_pid" 2>/dev/null || true
    wait "$smoke_launcher_pid" 2>/dev/null || true
    launcher_status=0
    smoke_launcher_pid=""
else
    set +e
    timeout "$timeout_seconds" xvfb-run -a env PATH="$path_value" CPLUS_LSP_TRACE="$trace_file" \
        IDEA_PROPERTIES="$smoke_dir/idea.properties" "$idea_launcher" \
        "$source_file" >"$smoke_dir/launcher.log" 2>&1
    launcher_status=$?
    set -e
fi

log_file="$smoke_dir/system/log/idea.log"
if [[ ! -f "$log_file" ]]; then
    keep_dir=1
    echo "IntelliJ did not produce a log (launcher status $launcher_status)" >&2
    exit 1
fi

if ! grep -Fq "Loaded custom plugins: C-plus" "$log_file"; then
    keep_dir=1
    echo "C-plus plugin was not loaded (launcher status $launcher_status)" >&2
    exit 1
fi
if ! grep -Fq "C-plus LSP fileOpened:" "$log_file"; then
    keep_dir=1
    echo "C-plus file activation did not reach the LSP integration provider (launcher status $launcher_status)" >&2
    exit 1
fi
if ! grep -Fq "C-plus LSP starting project client:" "$log_file"; then
    keep_dir=1
    echo "C-plus file activation was observed, but the provider did not request a client" >&2
    exit 1
fi
if ! grep -Fq "LSP server initialized" "$log_file"; then
    keep_dir=1
    echo "C-plus LSP did not initialize (launcher status $launcher_status)" >&2
    exit 1
fi
if grep -Eq "exec: java: not found|Java runtime not found" "$log_file" "$smoke_dir/launcher.log"; then
    keep_dir=1
    echo "C-plus LSP launcher lost its Java runtime or could not create the process" >&2
    exit 1
fi
if grep -Fq "CPlusParserExternalAnnotator cannot be cast" "$log_file"; then
    keep_dir=1
    echo "C-plus parser annotator registration failed" >&2
    exit 1
fi
if [[ ! -f "$trace_file" ]] || ! grep -Fq "in method=initialize" "$trace_file"; then
    keep_dir=1
    echo "C-plus LSP did not receive initialize (launcher status $launcher_status)" >&2
    exit 1
fi
if ! grep -Fq "in method=textDocument/didOpen" "$trace_file"; then
    keep_dir=1
    echo "C-plus LSP did not receive document synchronization (launcher status $launcher_status)" >&2
    exit 1
fi
if ! grep -Fq "out method=textDocument/publishDiagnostics" "$trace_file"; then
    keep_dir=1
    echo "C-plus LSP did not publish document diagnostics (launcher status $launcher_status)" >&2
    exit 1
fi

echo "IntelliJ C-plus LSP smoke passed"
echo "  plugin: $plugin_zip"
echo "  source: $source_file"
echo "  protocol trace: initialize, didOpen, publishDiagnostics"
echo "  launcher status: $launcher_status (timeout is acceptable after initialization)"
if [[ "$restart_probe" == "1" ]]; then
    echo "  restart probe: exact LSP child exit followed by a second initialization"
fi
