# C-plus toolchain bundle management

Status: **initial command implementation validated against release 0.1.1**

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

The release catalog is fetched from:

```text
https://github.com/alfu32/cplus-sysroots/releases/latest/download/triples.txt
```

The source repository also maintains the catalog at
`https://raw.githubusercontent.com/alfu32/cplus-sysroots/master/triples.txt`;
the release asset is used by the CLI so the catalog and bundles come from the
same published release.

Each non-comment catalog line has this form:

```text
<target-triple>-dev
<target-triple>-rt
```

`dev` contains development files. `rt` contains files needed by dynamically
linked programs at runtime. Releases publish one ZIP per catalog reference,
plus a `MANIFEST.json`, checksum, and metadata asset for that reference.

The latest release download convention is:

```text
https://github.com/alfu32/cplus-sysroots/releases/latest/download/<reference>.zip
```

The implementation validates the release JSON metadata, the published SHA-256
asset, and the extracted `MANIFEST.json` before accepting a bundle. It writes
the resolved release tag and digest to the nearest project `cplus.lock`.

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
cpc toolchain install arm64-apple-darwin-dev
cpc toolchain update                         # all published references
cpc toolchain update x86_64-w64-mingw32      # both dev and rt
```

A base triple selects both `-dev` and `-rt`. An exact `-dev` or `-rt`
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
<storage-root>/<target-triple>-dev/
<storage-root>/<target-triple>-rt/
```

## Installation algorithm and invariants

1. Fetch and parse the upstream catalog.
2. Expand a base triple into its published `dev` and `rt` references.
3. Download the release JSON metadata and verify its reference.
4. Download the ZIP and its `.sha256` asset into temporary files.
5. Verify the ZIP digest, then extract it into a temporary directory with
   ZIP-slip protection.
6. Require `MANIFEST.json` and verify its `reference` field.
7. Replace the selected local bundle only after validation succeeds.
8. Record the release tag, digest, target, and kind in `cplus.lock`, with
   toolchain references sorted alphabetically.
9. Remove temporary files on success and failure.

If a lock entry already exists, `install` uses its release tag and rejects a
changed published checksum. `update` intentionally resolves the current
latest release and refreshes the lock entry. This keeps ordinary installs
reproducible while making updates explicit.

No bundle is accepted merely because its download returned HTTP 200. The
catalog prevents arbitrary references from being requested, and the manifest
prevents an archive from being installed under the wrong target.

## Failure behavior

The command fails for an unavailable catalog, unknown reference, non-success
download response, unsupported/malformed archive, unsafe archive path, missing
manifest, or manifest/reference mismatch. `list local` remains useful offline;
`list remote`, `install`, and `update` require network access to the upstream
catalog or release.

## Compiler integration

The compiler automatically checks the same user-local storage before invoking
the selected C compiler. An installed `<canonical-triple>-dev` bundle is used
when no explicit `--sysroot` was supplied:

- Clang and GCC receive `--sysroot=<bundle>`.
- TCC receives the bundle's `usr/include` through `-I`. TCC does not support
  GCC/Clang's `--sysroot` option and must retain its own native CRT/linker
  configuration; injecting the bundle's glibc linker-script `libc.so` through
  `-L` would be invalid.

The selected target may be a canonical sysroot triple or a C-plus spelling such
as `linux-x86_64`, `macos-aarch64`, or `windows-x86_64`. Explicit sysroot
options always take precedence over discovery.

## Signature status and future extensions

- SHA-256 integrity verification and release pinning are implemented. The
  current upstream release publishes checksums but no detached signature or
  trusted public-key contract, so cryptographic signature verification cannot
  yet be performed without inventing a trust root.
- explicit remove, repair, and garbage-collection commands;
- CI fixtures against a published sysroots release.
