# Modules and dependency-management follow-up

## Completed first transition

| ID | Status | Work |
| --- | --- | --- |
| MOD-1 | done | Define local path dependency model and preserve existing manifest keys |
| MOD-2 | done | Define `stdlib = "auto"` and project fallback semantics |
| MOD-3 | done | Define dependency-first graph and safety invariants |
| MOD-4 | done | Parse `[dependencies]` local path tables and scaffold `stdlib = "auto"` |
| MOD-5 | done | Resolve transitive local module roots and reject missing manifests, name mismatches, and cycles |
| MOD-6 | done | Reuse manifest-derived module roots from the existing transcode, compile, run, test, and graph paths |
| MOD-7 | done | Add valid dependency and cycle regression coverage |

## Implementation phases

| ID | Status | Exit condition |
| --- | --- | --- |
| MOD-8 | done | Expose project/module roots and stdlib in IntelliJ |
| MOD-9 | done | Expose project/module roots and stdlib completions in VS Code |
| MOD-10 | done | Document package publishing and future registry/lockfile design |

## Package command implementation

The package-management goal is considered complete for the initial local and
direct-download milestone when PKG-1 through PKG-6 are done. PKG-7 and later
are a new follow-up project, not an implicit part of the current implementation.

| ID | Status | Work / exit condition |
| --- | --- | --- |
| PKG-1 | done | Add `cpc pkg init [folder]` with manifest-only output |
| PKG-2 | done | Add `cpc pkg add name=path-or-url` manifest mutation |
| PKG-3 | done | Stage and validate local, `file:`, and HTTP(S) ZIP packages |
| PKG-4 | done | Resolve transitive dependencies, detect cycles, and flatten into `modules/` |
| PKG-5 | done | Move staged packages only after complete validation and refuse overwrites |
| PKG-6 | done | Add CLI regression tests and package command documentation |
| PKG-7 | open | Define registry discovery and package publishing protocol |
| PKG-8 | open | Add semver constraints, version conflict handling, and deterministic selection |
| PKG-9 | open | Add lockfile format, checksums, and reproducible installations |
| PKG-10 | open | Add update, remove, upgrade, and existing-module replacement policy |
| PKG-11 | open | Add non-ZIP archive support and remote-download integration tests |
| PKG-12 | open | Add package-manager support to IntelliJ and VS Code workflows |

## Current status summary

| Area | Status | Current state |
| --- | --- | --- |
| Manifest-only initialization | done | `cpc pkg init [folder]` creates only `cplus.toml` |
| Dependency declaration | done | `cpc pkg add` writes path or URL dependency entries |
| Local recursive installation | done | Dependencies are staged, validated, recursively resolved, and flattened |
| Direct ZIP downloads | done | HTTP(S) `.zip` and `file:` references are supported |
| Install safety | done | Cycles, missing manifests, unsafe ZIP paths, name mismatches, and collisions fail before publication |
| CLI regression coverage | done | Init, add, transitive flattening, and invalid-package tests pass |
| Registry and version solving | open | No registry, semver solver, lockfile, or checksums yet |
| Editor/package workflow | open | Editors consume installed modules but do not manage packages yet |

## Explicit non-goals for this iteration

- No registry discovery protocol.
- No semver solver or lockfile.
- No automatic replacement of an existing `modules/<name>` directory.
- No TAR/RAR or non-ZIP HTTP archive support.
