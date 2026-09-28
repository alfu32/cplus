#!/usr/bin/env bash
set -euo pipefail

release_tag=${1:?usage: download-tinycc.sh <release-tag|latest> <asset.jar>...}
shift
if (($# == 0)); then
  echo "At least one TinyCC CLI asset must be specified" >&2
  exit 2
fi

github_headers=(
  --header "Accept: application/vnd.github+json"
  --header "User-Agent: c-plus-tinycc-downloader"
)
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  github_headers+=(--header "Authorization: Bearer $GITHUB_TOKEN")
fi

github_get() {
  curl --fail --location --retry 3 --retry-delay 2 --silent --show-error \
    "${github_headers[@]}" "$@"
}

if [[ "$release_tag" == "latest" ]]; then
  release_tag="$(github_get \
    https://api.github.com/repos/alfu32/tinycc/releases/latest | jq --exit-status --raw-output '.tag_name')"
fi

if [[ ! "$release_tag" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Invalid TinyCC release tag: $release_tag" >&2
  exit 2
fi

download_dir=${TINYCC_DOWNLOAD_DIR:-lib}
mkdir -p "$download_dir"

download_asset() {
  local local_name=$1
  local release_name=$local_name
  local destination="$download_dir/$local_name"
  local temporary="$destination.part"

  case "$local_name" in
    tinycc-cli.jar|tinycc-cli-linux-x86_64.jar|tinycc-cli-linux-aarch64.jar|\
    tinycc-cli-macos-x86_64.jar|tinycc-cli-macos-aarch64.jar|\
    tinycc-cli-windows-x86_64.jar|tinycc-cli-windows-aarch64.jar)
      ;;
    tinycc-cross-cli-no-sysroots.jar)
      ;;
    tinycc-cli-cross.jar)
      # The upstream release calls this asset tinycc-cross-cli.jar; Gradle's
      # established local filename remains tinycc-cli-cross.jar.
      release_name=tinycc-cross-cli.jar
      ;;
    *)
      echo "Unsupported TinyCC CLI asset: $local_name" >&2
      return 2
      ;;
  esac

  echo "Downloading TinyCC $release_tag: $release_name"
  local release_json
  release_json="$(github_get "https://api.github.com/repos/alfu32/tinycc/releases/tags/$release_tag")"
  local asset_url
  asset_url="$(printf '%s' "$release_json" | jq --exit-status --raw-output \
    --arg name "$release_name" '.assets[] | select(.name == $name) | .browser_download_url')"
  github_get "$asset_url" --output "$temporary"
  test -s "$temporary"
  mv -f "$temporary" "$destination"
}

download_asset tinycc-cli.jar
for asset in "$@"; do
  if [[ "$asset" != "tinycc-cli.jar" ]]; then
    download_asset "$asset"
  fi
done
