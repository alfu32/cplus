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
| MOD-10 | open | Document package publishing and future registry/lockfile design |

## Explicit non-goals for this iteration

- No registry download protocol.
- No semver solver.
- No automatic mutation of `modules/`.
- No lockfile until the local graph and diagnostics are stable.
