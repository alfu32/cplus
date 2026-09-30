#!/usr/bin/env bash
set -euo pipefail

# Launch the installed IntelliJ IDEA against an isolated profile, install the
# packaged C-plus plugin, and verify the real LSP client reaches Running state.
# This is intentionally a startup/document-activation smoke test; feature and
# crash/restart assertions remain separate acceptance tasks.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plugin_zip="${1:-}"
source_file="${2:-}"
idea_launcher="${IDEA_LAUNCHER:-}"
timeout_seconds="${INTELLIJ_SMOKE_TIMEOUT:-180}"

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
cleanup() {
    if (( keep_dir )); then
        echo "IntelliJ smoke files retained at: $smoke_dir" >&2
    else
        rm -rf "$smoke_dir" 2>/dev/null || true
    fi
}
trap cleanup EXIT

mkdir -p "$smoke_dir/config/plugins"
unzip -q -o "$plugin_zip" -d "$smoke_dir/config/plugins"
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

set +e
timeout "$timeout_seconds" xvfb-run -a env PATH="$path_value" "$idea_launcher" \
    "-Didea.config.path=$smoke_dir/config" \
    "-Didea.system.path=$smoke_dir/system" \
    "$source_file" >"$smoke_dir/launcher.log" 2>&1
launcher_status=$?
set -e

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

echo "IntelliJ C-plus LSP smoke passed"
echo "  plugin: $plugin_zip"
echo "  source: $source_file"
echo "  launcher status: $launcher_status (timeout is acceptable after initialization)"
