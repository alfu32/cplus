# Toolchain bundle follow-up

## Goal and milestone boundary

The initial milestone is complete when users can list the local/remote
catalog, install a published target bundle, and update one or all references in
user-local storage with manifest validation. It does not include compiler
selection, system compiler installation, or release publication itself.

## Implementation status

| ID | Status | Exit condition / current result |
| --- | --- | --- |
| TC-1 | done | Define release `triples.txt`, `-dev` and `-rt` references, and latest-release asset convention |
| TC-2 | done | Add user-local platform-aware storage with `CPLUS_TOOLCHAINS` override |
| TC-3 | done | Implement `toolchain list local` from installed `MANIFEST.json` files |
| TC-4 | done | Implement `toolchain list remote` from the upstream catalog |
| TC-5 | done | Implement exact-reference and base-triple installation selection |
| TC-6 | done | Download, extract safely, validate manifests, and replace bundles |
| TC-7 | done | Implement `toolchain update [reference|all]` |
| TC-8 | done | Add offline unit tests for catalog parsing, selection, and local listing |
| TC-9 | done | Document command semantics, storage, invariants, and failure behavior |
| TC-10 | done | Verify installation against the first published sysroots release |
| TC-11 | open | Verify compiler `--sysroot` discovery consumes installed bundles |
| TC-12 | done | Verify release JSON metadata and published SHA-256 checksums |
| TC-13 | open | Add signature verification and trusted release pinning |
| TC-14 | open | Add remove/repair/garbage-collection lifecycle commands |

## Validation plan

### Local/offline gate

- Parse a representative catalog with comments, duplicates, and unknown kinds.
- Confirm base triple selection returns both `dev` and `rt`.
- Confirm exact reference selection returns one bundle.
- Create local `MANIFEST.json` fixtures and verify `list local` output.
- Test malformed/unsafe archives once a fixture injection seam is added.

### Release gate

After `cplus-sysroots` publishes its first release:

1. Run `cpc toolchain list remote`.
2. Install one Linux, macOS, and Windows reference where supported.
3. Confirm `list local` reports the installed references.
4. Run `update <base-triple>` and `update all`.
5. Verify the extracted manifest, sysroot paths, checksums, and compiler integration.

### Known current limitation

Release `0.1.1` is now published. The Linux x86_64 runtime bundle was
installed successfully; release catalog listing, metadata validation, SHA-256
verification, archive extraction, and local discovery all passed. Compiler
sysroot consumption remains the next integration gate.
