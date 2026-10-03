# C-plus toolchain bundle management

Status: **initial command implementation complete; remote publication is pending**

## Goal

Give C-plus users a discoverable, user-local way to obtain the development and
runtime sysroot bundles produced by `alfu32/cplus-sysroots`, without putting
those large artifacts in the C-plus repository or requiring system-wide
installation.

The command manages sysroot bundles, not compiler executables. The compiler
selection policy remains the existing host/compiler configuration; this
feature provides the target headers, startup objects, libraries, linker data,
and runtime files that a configured compiler can consume.

## Upstream contract

The catalog is fetched from:

```text
https://raw.githubusercontent.com/alfu32/cplus-sysroots/master/triples.txt
```

Each non-comment catalog line has this form:

```text
<target-triple>/dev
<target-triple>/rt
```

`dev` contains development files. `rt` contains files needed by dynamically
linked programs at runtime. Releases publish one ZIP per catalog reference,
plus a `MANIFEST.json`, checksum, and metadata asset for that reference.

The latest release download convention is:

```text
https://github.com/alfu32/cplus-sysroots/releases/latest/download/<reference>.zip
```

The implementation validates the extracted `MANIFEST.json` and its declared
reference before accepting a bundle.

## CLI contract

```text
cpc toolchain list [local|remote]
cpc toolchain install <triple-or-reference>
cpc toolchain update [triple-or-reference|all]
```

Examples:

```text
cpc toolchain list remote
cpc toolchain install x86_64-unknown-linux-gnu
cpc toolchain install aarch64-apple-darwin/dev
cpc toolchain update                         # all published references
cpc toolchain update x86_64-w64-mingw32      # both dev and rt
```

A base triple selects both `/dev` and `/rt`. An exact `/dev` or `/rt`
reference selects only that bundle. `list local` prints references whose local
bundle contains a validated `MANIFEST.json`; `list remote` prints the upstream
catalog.

## Storage policy

The default storage root is user-local:

| Host | Default root |
| --- | --- |
| Linux/Unix | `$XDG_DATA_HOME/cplus/toolchains`, or `~/.local/share/cplus/toolchains` |
| macOS | `~/Library/Application Support/cplus/toolchains` |
| Windows | `%LOCALAPPDATA%/cplus/toolchains`, or `%USERPROFILE%/AppData/Local/cplus/toolchains` |

`CPLUS_TOOLCHAINS` overrides the storage root. A JVM system property named
`cplus.toolchains` is also supported for isolated tests and embedding.

Bundles are stored as:

```text
<storage-root>/<target-triple>/dev/
<storage-root>/<target-triple>/rt/
```

## Installation algorithm and invariants

1. Fetch and parse the upstream catalog.
2. Expand a base triple into its published `dev` and `rt` references.
3. Download each reference's ZIP into a temporary file.
4. Extract into a temporary directory with ZIP-slip protection.
5. Require `MANIFEST.json` and verify its `reference` field.
6. Replace the selected local bundle only after validation succeeds.
7. Remove temporary files on success and failure.

No bundle is accepted merely because its download returned HTTP 200. The
catalog prevents arbitrary references from being requested, and the manifest
prevents an archive from being installed under the wrong target.

## Failure behavior

The command fails for an unavailable catalog, unknown reference, non-success
download response, unsupported/malformed archive, unsafe archive path, missing
manifest, or manifest/reference mismatch. `list local` remains useful offline;
`list remote`, `install`, and `update` require network access to the upstream
catalog or release.

## Future extensions

- checksum verification against the published `.sha256` asset;
- signature verification and trusted release pinning;
- release/version selection instead of only `latest`;
- explicit remove, repair, and garbage-collection commands;
- integration with compiler `--sysroot` discovery and project configuration;
- CI fixtures against a published sysroots release.
